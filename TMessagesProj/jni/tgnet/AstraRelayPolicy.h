#pragma once

#include <cstdint>

namespace astragram {

enum class RelayPermission { Unknown, Allowed, Denied };
enum class RelayRoute { WaitingForAccess, Relay, Direct };

// Pure monotonic-clock policy; shared by every account and tgnet connection.
class RelayPolicy {
public:
    static constexpr int64_t recoveryMs = 30000;
    static constexpr int64_t retryMs = 3600000;
    static constexpr int64_t permissionMs = 86400000;

    void access(RelayPermission value, int64_t now, int64_t duration) {
        RelayPermission previous = permission;
        permission = value;
        permissionUntil = now + duration;
        if (value == RelayPermission::Denied) {
            deniedUntil = permissionUntil;
            recoveringSince = -1;
            cooldownUntil = 0;
        } else if (value == RelayPermission::Allowed) {
            deniedUntil = 0;
            awaitingSince = -1;
            if (previous != RelayPermission::Allowed) recoveringSince = -1;
        }
    }

    RelayRoute route(int64_t now) {
        if (permission == RelayPermission::Denied && now < deniedUntil) return RelayRoute::Direct;
        if (permission == RelayPermission::Denied || permission == RelayPermission::Allowed && now >= permissionUntil) {
            permission = RelayPermission::Unknown;
            awaitingSince = -1;
        }
        if (cooldownUntil > now) return RelayRoute::Direct;
        if (cooldownUntil != 0) {
            cooldownUntil = 0;
            recoveringSince = -1;
            awaitingSince = -1;
        }
        if (permission == RelayPermission::Unknown) {
            if (awaitingSince < 0) awaitingSince = now;
            if (now - awaitingSince < recoveryMs) return RelayRoute::WaitingForAccess;
            cooldownUntil = now + retryMs;
            awaitingSince = -1;
            return RelayRoute::Direct;
        }
        if (recoveringSince >= 0 && now - recoveringSince >= recoveryMs) {
            cooldownUntil = now + retryMs;
            recoveringSince = -1;
            return RelayRoute::Direct;
        }
        return RelayRoute::Relay;
    }

    void attempting(int64_t now) {
        if (permission == RelayPermission::Allowed && recoveringSince < 0) recoveringSince = now;
    }
    void connected() { recoveringSince = -1; cooldownUntil = 0; }
    void networkLost() { recoveringSince = -1; awaitingSince = -1; }
    int64_t attemptRemaining(int64_t now) const {
        return recoveringSince < 0 ? recoveryMs : recoveryMs - (now - recoveringSince);
    }
    int64_t retryAt() const { return cooldownUntil; }
    RelayPermission accessState() const { return permission; }
    int64_t accessUntil() const { return permissionUntil; }

    void restoreCooldown(int64_t now, int64_t remaining) {
        if (permission != RelayPermission::Denied && remaining > 0 && remaining <= retryMs) cooldownUntil = now + remaining;
    }

private:
    RelayPermission permission = RelayPermission::Unknown;
    int64_t permissionUntil = 0;
    int64_t deniedUntil = 0;
    int64_t awaitingSince = -1;
    int64_t recoveringSince = -1;
    int64_t cooldownUntil = 0;
};

}
