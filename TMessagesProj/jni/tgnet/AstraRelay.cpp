#include "AstraRelay.h"
#include "AstraWebSocket.h"
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cctype>
#include <fcntl.h>
#include <map>
#include <mutex>
#include <poll.h>
#include <sstream>
#include <thread>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <netinet/tcp.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/sha.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>

namespace astragram {
namespace {
int64_t mono() {
    timespec value{};
    clock_gettime(CLOCK_BOOTTIME,&value);
    return int64_t(value.tv_sec)*1000+value.tv_nsec/1000000;
}
std::mutex configurationMutex;
RelayConfiguration configuration;
bool relayEligible = false;
RelayPolicy policy;
RelayProof proofProvider;
RelayEvent eventReceiver;
uint64_t configurationGeneration = 0;
int64_t lastAccessRequest = -10000;
std::vector<std::weak_ptr<AstraRelayTransport>> transports;

struct Snapshot {
    RelayConfiguration config;
    RelayRoute route;
    RelayProof proof;
    RelayEvent event;
    uint64_t generation;
    int64_t remaining;
    int64_t wakeupMs;
};
std::vector<std::shared_ptr<AstraRelayTransport>> activeLocked() {
    std::vector<std::shared_ptr<AstraRelayTransport>> result;
    for (auto it = transports.begin(); it != transports.end();) {
        if (auto transport = it->lock()) { result.push_back(std::move(transport)); ++it; }
        else it = transports.erase(it);
    }
    return result;
}
Snapshot snapshot() {
    Snapshot result;
    bool requestAccess = false;
    int64_t cooldown = 0;
    {
        std::lock_guard<std::mutex> lock(configurationMutex);
        int64_t now = mono(), previousRetry = policy.retryAt();
        result.route = configuration.networkAvailable && relayEligible ? policy.route(now) : RelayRoute::Direct;
        result.config = configuration;
        result.proof = proofProvider; result.event = eventReceiver;
        result.generation = configurationGeneration;
        result.remaining = std::max<int64_t>(0, policy.attemptRemaining(now));
        result.wakeupMs=30000;
        if(policy.retryAt()>now)result.wakeupMs=std::min<int64_t>(result.wakeupMs,policy.retryAt()-now);
        if(policy.accessUntil()>now)result.wakeupMs=std::min<int64_t>(result.wakeupMs,policy.accessUntil()-now);
        if (policy.retryAt() != previousRetry && policy.retryAt() > now) cooldown = policy.retryAt() - now;
        if (policy.accessState() == RelayPermission::Unknown && now - lastAccessRequest >= 5000 && result.config.networkAvailable && result.route != RelayRoute::Direct) {
            lastAccessRequest = now; requestAccess = true;
        }
    }
    if (result.event) {
        if (cooldown) result.event(1, cooldown);
        if (requestAccess) result.event(0, 0);
    }
    return result;
}
void administrativelyDenied() {
    RelayEvent event;
    std::vector<std::shared_ptr<AstraRelayTransport>> wake;
    {
        std::lock_guard<std::mutex> lock(configurationMutex);
        policy.access(RelayPermission::Denied, mono(), RelayPolicy::permissionMs);
        event = eventReceiver; wake = activeLocked();
    }
    for (const auto& transport : wake) transport->wake();
    if (event) event(2, RelayPolicy::permissionMs);
}
bool connected(uint64_t generation) {
    RelayEvent event;
    {
        std::lock_guard<std::mutex> lock(configurationMutex);
        int64_t now=mono();
        if (!relayEligible || generation != configurationGeneration || policy.accessState()!=RelayPermission::Allowed || policy.accessUntil()<=now
                || policy.retryAt()>now || policy.attemptRemaining(now)<=0) return false;
        policy.connected(); event = eventReceiver;
    }
    if (event) event(3, 0);
    return true;
}
// Each accepted WSS stream contributes exactly once, including early returns.
struct ActiveRelayConnection {
    bool active = false;
    void release() {
        if (!active) return;
        std::lock_guard<std::mutex> lock(configurationMutex);
        policy.disconnected();
        active = false;
    }
    ~ActiveRelayConnection() { release(); }
};
bool cleanHeader(const std::string& value) { return value.find_first_of("\r\n") == std::string::npos; }
std::string hex(const uint8_t* value, size_t length) {
    static const char digits[] = "0123456789abcdef";
    std::string result(length * 2, '0');
    for (size_t i = 0; i < length; i++) { result[i*2] = digits[value[i] >> 4]; result[i*2+1] = digits[value[i] & 15]; }
    return result;
}
std::string base64(const uint8_t* value, size_t length) {
    std::string result(4 * ((length + 2) / 3) + 1, '\0');
    int count = EVP_EncodeBlock(reinterpret_cast<uint8_t*>(&result[0]), value, length);
    if (count < 0) return {}; result.resize(count); return result;
}
ssl_verify_result_t verifyRelayCertificate(SSL* ssl, uint8_t*) {
    auto* config = static_cast<const RelayConfiguration*>(SSL_get_app_data(ssl));
    X509* leaf = SSL_get_peer_certificate(ssl);
    if (!config || !leaf) return ssl_verify_invalid;
    bool valid = X509_check_host(leaf, config->host.c_str(), config->host.size(), X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS, nullptr) == 1
        && X509_cmp_current_time(X509_get0_notBefore(leaf)) < 0 && X509_cmp_current_time(X509_get0_notAfter(leaf)) > 0;
    X509_PUBKEY* key = X509_get_X509_PUBKEY(leaf);
    int size = key ? i2d_X509_PUBKEY(key, nullptr) : 0;
    if (valid && size > 0 && size <= 4096) {
        std::vector<uint8_t> der(size); uint8_t* write = der.data();
        i2d_X509_PUBKEY(key, &write);
        uint8_t hash[SHA256_DIGEST_LENGTH]; SHA256(der.data(), der.size(), hash);
        std::string pin = hex(hash, sizeof(hash)); valid = false;
        for (const auto& expected : config->pins) if (expected.size() == pin.size() && CRYPTO_memcmp(expected.data(), pin.data(), pin.size()) == 0) valid = true;
    } else valid = false;
    X509_free(leaf);
    return valid ? ssl_verify_ok : ssl_verify_invalid;
}
struct FD {
    int value = -1;
    explicit FD(int value = -1) : value(value) {}
    ~FD() { if (value >= 0) close(value); }
    FD(const FD&) = delete; FD& operator=(const FD&) = delete;
};
struct TLS {
    SSL_CTX* context = nullptr;
    SSL* ssl = nullptr;
    ~TLS() { if (ssl) SSL_free(ssl); if (context) SSL_CTX_free(context); }
};
struct Queue {
    static constexpr size_t limit = 262144;
    std::vector<uint8_t> data;
    size_t offset = 0;
    size_t size() const { return data.size() - offset; }
    const uint8_t* bytes() const { return data.data() + offset; }
    void discard(size_t length) { offset += length; if (offset == data.size()) { data.clear(); offset = 0; } }
    bool append(const uint8_t* value, size_t length) {
        if (length > limit || size() + length > limit) return false;
        if (!length) return true;
        if (offset) { data.erase(data.begin(), data.begin() + offset); offset = 0; }
        data.insert(data.end(), value, value + length); return true;
    }
};
std::string lower(std::string value) { for (char& c : value) c = char(std::tolower(static_cast<unsigned char>(c))); return value; }
std::string trim(std::string value) { size_t a = value.find_first_not_of(" \t"), b = value.find_last_not_of(" \t"); return a == std::string::npos ? "" : value.substr(a,b-a+1); }
}

void configureAstraRelay(RelayConfiguration value, RelayProof proof, RelayEvent event) {
    std::vector<std::shared_ptr<AstraRelayTransport>> wake;
    {
        std::lock_guard<std::mutex> lock(configurationMutex);
        if (configuration.networkAvailable && !value.networkAvailable) policy.networkLost();
        configuration = std::move(value); proofProvider = std::move(proof); eventReceiver = std::move(event);
        policy.access(configuration.permission, mono(), std::min<int64_t>(RelayPolicy::permissionMs, std::max<int64_t>(0, configuration.validityMs)));
        policy.restoreCooldown(mono(), configuration.retryMs);
        configurationGeneration++;
        wake = activeLocked();
    }
    for (const auto& transport : wake) transport->wake();
}
bool astraRelayManaged() { std::lock_guard<std::mutex> lock(configurationMutex); return configuration.managed; }
void setAstraRelayEligible(bool eligible) {
    std::vector<std::shared_ptr<AstraRelayTransport>> wake;
    {
        std::lock_guard<std::mutex> lock(configurationMutex);
        if (relayEligible == eligible) return;
        relayEligible = eligible;
        configurationGeneration++;
        wake = activeLocked();
    }
    for (const auto& transport : wake) transport->wake();
}

AstraRelayTransport::AstraRelayTransport(const std::string& address, uint16_t port, int peer, int wake)
    : address(address), port(port), peerFd(peer), wakeFd(wake) {}
AstraRelayTransport::~AstraRelayTransport() { stop(); close(peerFd); close(wakeFd); }
void AstraRelayTransport::wake() { uint64_t value = 1; (void) ::write(wakeFd, &value, sizeof(value)); }
void AstraRelayTransport::stop() { stopping.store(true); wake(); }
void AstraRelayTransport::notify(int event, int64_t delay) { RelayEvent receiver; { std::lock_guard<std::mutex> lock(configurationMutex); receiver = eventReceiver; } if (receiver) receiver(event, delay); }

std::shared_ptr<AstraRelayTransport> AstraRelayTransport::open(const std::string& address, uint16_t port, int& localSocket) {
    localSocket = -1;
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) != 0) return {};
    int wake = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (wake < 0) { close(pair[0]); close(pair[1]); return {}; }
    auto transport = std::shared_ptr<AstraRelayTransport>(new AstraRelayTransport(address,port,pair[1],wake));
    { std::lock_guard<std::mutex> lock(configurationMutex); activeLocked(); transports.push_back(transport); }
    localSocket = pair[0];
    std::thread([transport] { transport->run(); }).detach();
    return transport;
}

bool AstraRelayTransport::wait(int networkFd, short events, int64_t deadline) {
    while (!stopping.load() && mono() < deadline) {
        pollfd descriptors[2] = {{wakeFd,POLLIN,0},{networkFd,events,0}};
        int result = poll(descriptors, 2, int(std::min<int64_t>(1000, deadline - mono())));
        if (result < 0 && errno == EINTR) continue;
        if (result < 0) return false;
        if (descriptors[0].revents) { uint64_t value; while (::read(wakeFd,&value,sizeof(value)) > 0) {} return false; }
        if (descriptors[1].revents) return true;
    }
    return false;
}
int AstraRelayTransport::connectTCP(const std::string& address, uint16_t port, int64_t deadline) {
    sockaddr_storage storage{}; socklen_t size;
    if (address.find(':') != std::string::npos) {
        auto* target = reinterpret_cast<sockaddr_in6*>(&storage); target->sin6_family = AF_INET6; target->sin6_port = htons(port);
        if (inet_pton(AF_INET6,address.c_str(),&target->sin6_addr) != 1) return -1; size = sizeof(*target);
    } else {
        auto* target = reinterpret_cast<sockaddr_in*>(&storage); target->sin_family = AF_INET; target->sin_port = htons(port);
        if (inet_pton(AF_INET,address.c_str(),&target->sin_addr) != 1) return -1; size = sizeof(*target);
    }
    int fd = socket(storage.ss_family,SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC,0); if (fd < 0) return -1;
    int result = connect(fd,reinterpret_cast<sockaddr*>(&storage),size);
    if (result < 0 && (errno != EINPROGRESS || !wait(fd,POLLOUT,deadline))) { close(fd); return -1; }
    int error = 0; socklen_t length = sizeof(error);
    if (getsockopt(fd,SOL_SOCKET,SO_ERROR,&error,&length) != 0 || error != 0) { close(fd); return -1; }
    int yes = 1; setsockopt(fd,IPPROTO_TCP,TCP_NODELAY,&yes,sizeof(yes));
    return fd;
}

void AstraRelayTransport::run() {
    while (!stopping.load()) {
        Snapshot current = snapshot();
        if (!current.config.managed) break;
        if (!current.config.networkAvailable || current.route == RelayRoute::WaitingForAccess) { wait(-1,0,mono()+1000); continue; }
        bool relay = current.route == RelayRoute::Relay;
        if (relay && (current.config.ips.empty() || current.config.pins.empty() || current.config.token.empty() || current.config.port == 0
                || current.config.path != "/v1/tunnel" || !cleanHeader(current.config.host) || !cleanHeader(current.config.token))) {
            { std::lock_guard<std::mutex> lock(configurationMutex); policy.attempting(mono()); }
            notify(0,0); wait(-1,0,mono()+1000); continue;
        }
        int64_t deadline = mono() + 10000;
        if (relay) {
            std::lock_guard<std::mutex> lock(configurationMutex);
            policy.attempting(mono()); deadline = mono() + std::min<int64_t>(8000,std::max<int64_t>(0,policy.attemptRemaining(mono())));
        }
        int fd = -1;
        if (relay) for (const auto& ip : current.config.ips) { fd = connectTCP(ip,current.config.port,deadline); if (fd >= 0 || stopping.load() || mono() >= deadline) break; }
        else fd = connectTCP(address,port,deadline);
        if (fd < 0) { if (!relay) break; wait(-1,0,mono()+1000); continue; }
        FD network(fd);
        TLS tls;
        ActiveRelayConnection activeConnection;
        std::vector<uint8_t> initial;
        auto sslWait = [&](int result, int64_t until) {
            int error = SSL_get_error(tls.ssl,result);
            return (error == SSL_ERROR_WANT_READ || error == SSL_ERROR_WANT_WRITE) && wait(fd,error == SSL_ERROR_WANT_READ ? POLLIN : POLLOUT,until);
        };
        bool handshake = !relay;
        if (relay) {
            tls.context = SSL_CTX_new(TLS_method());
            if (!tls.context) break;
            SSL_CTX_set_min_proto_version(tls.context,TLS1_2_VERSION);
            SSL_CTX_set_custom_verify(tls.context,SSL_VERIFY_PEER,verifyRelayCertificate);
            tls.ssl = SSL_new(tls.context);
            if (!tls.ssl) break;
            SSL_set_app_data(tls.ssl,&current.config);
            SSL_set_fd(tls.ssl,fd); SSL_set_connect_state(tls.ssl);
            SSL_set_tlsext_host_name(tls.ssl,current.config.host.c_str());
            SSL_set_mode(tls.ssl,SSL_MODE_ENABLE_PARTIAL_WRITE | SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER);
            int result;
            while (!stopping.load() && mono() < deadline) {
                result = SSL_do_handshake(tls.ssl);
                if (result == 1) { handshake = true; break; }
                if (!sslWait(result,deadline)) break;
            }
            if (!handshake) { notify(0,0); wait(-1,0,mono()+500); continue; }
            std::array<uint8_t,16> nonce{}; if (RAND_bytes(nonce.data(),nonce.size()) != 1) break;
            std::string key = base64(nonce.data(),nonce.size());
            std::string headers = current.proof ? current.proof(current.config.token,address,port) : "";
            if (headers.empty()) { notify(0,0); wait(-1,0,mono()+500); continue; }
            std::string request = "GET " + current.config.path + "?ip=" + address + "&port=" + std::to_string(port) + " HTTP/1.1\r\nHost: "
                + current.config.host + ":" + std::to_string(current.config.port)
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: astrarelay.v1\r\nSec-WebSocket-Key: " + key
                + "\r\nAuthorization: Bearer " + current.config.token + "\r\n" + headers + "\r\n";
            size_t sent = 0;
            while (sent < request.size() && !stopping.load() && mono() < deadline) {
                int written = SSL_write(tls.ssl,request.data()+sent,request.size()-sent);
                if (written > 0) sent += written;
                else if (!sslWait(written,deadline)) break;
            }
            if (sent != request.size()) continue;
            std::string response;
            size_t end = std::string::npos;
            while (!stopping.load() && mono() < deadline) {
                uint8_t block[8192]; int count = SSL_read(tls.ssl,block,sizeof(block));
                if (count > 0) {
                    response.append(reinterpret_cast<char*>(block),count);
                    end = response.find("\r\n\r\n");
                    if (end != std::string::npos || response.size() > 16384) break;
                } else if (!sslWait(count,deadline)) break;
            }
            if (end == std::string::npos || end > 16384) continue;
            std::istringstream lines(response.substr(0,end)); std::string line,version; int status = 0;
            std::getline(lines,line); std::istringstream first(line); first >> version >> status;
            if (version != "HTTP/1.1") continue;
            if (status == 403) { administrativelyDenied(); continue; }
            if (status == 401) { notify(0,0); continue; }
            if (status != 101) { wait(-1,0,mono()+500); continue; }
            std::map<std::string,std::string> fields; bool duplicate = false;
            while (std::getline(lines,line)) {
                if (!line.empty() && line.back() == '\r') line.pop_back();
                size_t colon = line.find(':'); if (colon == std::string::npos) { duplicate = true; break; }
                std::string name = lower(trim(line.substr(0,colon)));
                if (!fields.emplace(name,trim(line.substr(colon+1))).second) { duplicate = true; break; }
            }
            std::string acceptSource = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            uint8_t hash[SHA_DIGEST_LENGTH]; SHA1(reinterpret_cast<const uint8_t*>(acceptSource.data()),acceptSource.size(),hash);
            if (duplicate || fields["sec-websocket-accept"] != base64(hash,sizeof(hash)) || fields["sec-websocket-protocol"] != "astrarelay.v1"
                    || lower(fields["upgrade"]) != "websocket" || lower(fields["connection"]).find("upgrade") == std::string::npos || fields.count("sec-websocket-extensions")) continue;
            if (!connected(current.generation)) continue;
            activeConnection.active = true;
            initial.assign(response.begin()+end+4,response.end());
        }
        Queue networkOut, clientOut;
        uint8_t ready = 0xa7; clientOut.append(&ready,1);
        RelayFrameDecoder decoder;
        if (!initial.empty() && !decoder.feed(initial.data(),initial.size())) break;
        bool normalClose = false, reconfigured = false;
        bool readNeedsWrite = false, writeNeedsRead = false;
        size_t retryWriteLength = 0;
        auto appendFrame = [&](uint8_t opcode, const uint8_t* data, size_t length) {
            std::array<uint8_t,4> mask{}; if (RAND_bytes(mask.data(),mask.size()) != 1) return false;
            auto frame = relayMaskedFrame(opcode,data,length,mask);
            return !frame.empty() && networkOut.append(frame.data(),frame.size());
        };
        auto receiveFrames = [&]() {
            RelayFrame frame; int result;
            while ((result=decoder.next(frame)) == 1) {
                if (frame.opcode == 0 || frame.opcode == 2) { if (!clientOut.append(frame.payload.data(),frame.payload.size())) return false; }
                else if (frame.opcode == 9) { if (!appendFrame(10,frame.payload.data(),frame.payload.size())) return false; }
                else if (frame.opcode == 8) {
                    uint16_t code = frame.payload.size() >= 2 ? (uint16_t(frame.payload[0]) << 8) | frame.payload[1] : 1000;
                    if (code == 4003) administrativelyDenied();
                    else if (code == 4001) notify(0,0);
                    normalClose = true; return false;
                }
            }
            return result >= 0;
        };
        if (relay && !receiveFrames()) break;
        while (!stopping.load()) {
            Snapshot desired = snapshot();
            if (!desired.config.networkAvailable || relay && desired.route != RelayRoute::Relay || !relay && desired.route == RelayRoute::Relay) { reconfigured = true; break; }
            short localEvents = (networkOut.size() < Queue::limit/2 ? POLLIN : 0) | (clientOut.size() ? POLLOUT : 0);
            short remoteEvents = (clientOut.size() < Queue::limit/2 || writeNeedsRead ? POLLIN : 0) | (networkOut.size() || readNeedsWrite ? POLLOUT : 0);
            pollfd events[3] = {{wakeFd,POLLIN,0},{peerFd,localEvents,0},{fd,remoteEvents,0}};
            int timeout = relay && SSL_pending(tls.ssl) && clientOut.size() < Queue::limit/2 ? 0 : int(std::max<int64_t>(1,desired.wakeupMs));
            int result = poll(events,3,timeout);
            if (result < 0) { if (errno == EINTR) continue; break; }
            if (events[0].revents) { uint64_t value; while (::read(wakeFd,&value,sizeof(value)) > 0) {} continue; }
            if (events[1].revents & (POLLERR|POLLHUP|POLLRDHUP)) { normalClose = true; break; }
            if (events[1].revents & POLLOUT && clientOut.size()) {
                ssize_t written = send(peerFd,clientOut.bytes(),clientOut.size(),MSG_NOSIGNAL);
                if (written > 0) clientOut.discard(written); else if (written == 0 || errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) { normalClose = true; break; }
            }
            if (events[1].revents & POLLIN && networkOut.size() < Queue::limit/2) {
                uint8_t block[16384]; ssize_t count = recv(peerFd,block,sizeof(block),0);
                if (count > 0) { if (relay ? !appendFrame(2,block,count) : !networkOut.append(block,count)) break; }
                else if (count == 0 || errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) { normalClose = true; break; }
            }
            if (networkOut.size() && (events[2].revents & (POLLOUT|POLLIN))) {
                size_t size = retryWriteLength ? retryWriteLength : std::min<size_t>(16384,networkOut.size());
                int count = relay ? SSL_write(tls.ssl,networkOut.bytes(),size) : send(fd,networkOut.bytes(),size,MSG_NOSIGNAL);
                if (count > 0) { networkOut.discard(count); retryWriteLength = 0; writeNeedsRead = false; }
                else if (relay) {
                    int error = SSL_get_error(tls.ssl,count);
                    if (error != SSL_ERROR_WANT_READ && error != SSL_ERROR_WANT_WRITE) break;
                    retryWriteLength = size; writeNeedsRead = error == SSL_ERROR_WANT_READ;
                } else if (count == 0 || errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) break;
            }
            bool networkProgress = false;
            bool readable = events[2].revents & (POLLIN|POLLHUP|POLLRDHUP) || readNeedsWrite && (events[2].revents & POLLOUT) || relay && SSL_pending(tls.ssl);
            if (readable && clientOut.size() < Queue::limit/2) {
                uint8_t block[16384]; int count = relay ? SSL_read(tls.ssl,block,sizeof(block)) : recv(fd,block,sizeof(block),0);
                if (count > 0) {
                    networkProgress = true;
                    readNeedsWrite = false;
                    if (relay) { if (!decoder.feed(block,count) || !receiveFrames()) break; }
                    else if (!clientOut.append(block,count)) break;
                } else if (relay) {
                    int error = SSL_get_error(tls.ssl,count);
                    if (error != SSL_ERROR_WANT_READ && error != SSL_ERROR_WANT_WRITE) break;
                    readNeedsWrite = error == SSL_ERROR_WANT_WRITE;
                } else if (count == 0 || errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) break;
            }
            if ((events[2].revents & (POLLERR|POLLHUP|POLLRDHUP)) && !networkProgress && clientOut.size() < Queue::limit/2) break;
        }
        activeConnection.release();
        if (relay && !normalClose && !reconfigured && !stopping.load()) { std::lock_guard<std::mutex> lock(configurationMutex); policy.attempting(mono()); }
        break; // Never replay an encrypted stream into another TCP connection.
    }
    stopping.store(true);
    shutdown(peerFd,SHUT_RDWR);
}

}
