#pragma once
// LazyLuno - spoof.h
// Author: SparkyNox
// InnerTube client profiles + the order they are tried in.

#include <optional>
#include <string>
#include <vector>

namespace lazyluno {

enum class ClientType {
    WEB, ANDROID_APP, IOS, TVHTML5, TV_EMBEDDED, TVHTML5_SIMPLY, MWEB, VISIONOS, WEB_CREATOR,
};

// field order matters, client_spoof.cpp uses designated initializers
struct ClientProfile {
    ClientType  type = ClientType::WEB;
    std::string clientName;
    std::string clientVersion;
    std::string clientId;
    std::string osName;
    std::string osVersion;
    std::string deviceMake;
    std::string deviceModel;
    std::string platform;
    int         androidSdkVersion = 0;
    std::string userAgent;
    std::string apiKey;
    std::string innertubeHost;
    bool        requiresPoToken   = false;
    bool        supportsAgeBypass = false;
    bool        needsEmbedUrl     = false;
    std::string embedUrl;
};

struct SpoofConfig {
    std::vector<ClientType>   clientOrder;
    std::optional<ClientType> forceClient;
    bool                      appendAuthFallback = true;
    // known age-restricted video: go straight to the signed in clients instead of trying the anonymous ones first
    bool                      authFirst = false;

    static SpoofConfig defaultConfig() {
        SpoofConfig c;
        c.clientOrder = {ClientType::TV_EMBEDDED, ClientType::TVHTML5, ClientType::ANDROID_APP, ClientType::IOS};
        return c;
    }
    // same list, age bypass first and kept first
    static SpoofConfig ageBypassConfig() {
        SpoofConfig c;
        c.clientOrder = {ClientType::TV_EMBEDDED, ClientType::TVHTML5_SIMPLY, ClientType::TVHTML5,
                         ClientType::ANDROID_APP, ClientType::IOS};
        return c;
    }
};

ClientProfile getClientProfile(ClientType type);

std::string buildInnertubeContext(const ClientProfile& p,
                                  const std::string&   visitorData,
                                  const std::string&   poToken = {},
                                  const std::string&   hl = "en",
                                  const std::string&   gl = "US");

} // namespace lazyluno
