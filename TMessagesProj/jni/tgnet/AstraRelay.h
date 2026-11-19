#pragma once

#include "AstraRelayPolicy.h"
#include <atomic>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace astragram {

struct RelayConfiguration {
    bool managed = false;
    bool networkAvailable = true;
    RelayPermission permission = RelayPermission::Unknown;
    int64_t validityMs = 0;
    int64_t retryMs = 0;
    std::string host;
    std::vector<std::string> ips;
    uint16_t port = 0;
    std::string path;
    std::vector<std::string> pins;
    std::string token;
};

using RelayProof = std::function<std::string(const std::string&, const std::string&, uint16_t)>;
using RelayEvent = std::function<void(int, int64_t)>;

void configureAstraRelay(RelayConfiguration configuration, RelayProof proof, RelayEvent event);
bool astraRelayManaged();
void setAstraRelayEligible(bool eligible);

class AstraRelayTransport : public std::enable_shared_from_this<AstraRelayTransport> {
public:
    static std::shared_ptr<AstraRelayTransport> open(const std::string& address, uint16_t port, int& localSocket);
    ~AstraRelayTransport();
    void stop();
    void wake();
private:
    AstraRelayTransport(const std::string& address, uint16_t port, int peer, int wake);
    void run();
    bool wait(int networkFd, short networkEvents, int64_t deadline);
    int connectTCP(const std::string& address, uint16_t port, int64_t deadline);
    void notify(int event, int64_t delay);
    std::string address;
    uint16_t port;
    int peerFd;
    int wakeFd;
    std::atomic<bool> stopping{false};
};

}
