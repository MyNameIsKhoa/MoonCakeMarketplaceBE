package mss.mooncake.identity.service;

import mss.mooncake.identity.dto.request.RegisterRequest;
import mss.mooncake.identity.dto.response.UserSummaryResponse;

public interface AccountApplicationService {
    UserSummaryResponse register(RegisterRequest request);
    // TODO: Implement authentication and token issuance according to the selected identity provider.
}
