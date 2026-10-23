package mss.mooncake.rfqnegotiation.service;

import java.util.Map;
import mss.mooncake.rfqnegotiation.dto.request.CreateRfqRequest;
import mss.mooncake.rfqnegotiation.dto.response.PageResponse;
import mss.mooncake.rfqnegotiation.dto.response.RfqResponse;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;

public interface RfqApplicationService {
    RfqResponse create(CreateRfqRequest request);

    PageResponse<RfqResponse> list(String q, boolean mine, RfqStatus status, int page, int size, String sort);

    RfqResponse get(Long rfqId);

    RfqResponse update(Long rfqId, Map<String, Object> patch);

    RfqResponse close(Long rfqId);

    RfqResponse cancel(Long rfqId);
}
