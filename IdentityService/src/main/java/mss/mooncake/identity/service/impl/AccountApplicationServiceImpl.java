package mss.mooncake.identity.service.impl;
import mss.mooncake.identity.service.AccountApplicationService;

import mss.mooncake.identity.dto.request.RegisterRequest;
import mss.mooncake.identity.dto.response.UserSummaryResponse;
import org.springframework.stereotype.Service;

@Service
public class AccountApplicationServiceImpl implements AccountApplicationService {
    @Override
    public UserSummaryResponse register(RegisterRequest request) {
        throw new UnsupportedOperationException("TODO: implement account registration");
    }
}
