package mss.mooncake.commerce.dto.response;

import java.util.List;

/** Admin only. A QR prefills a manual transfer; it does not prove money was sent. */
public record TransferInstructionResponse(List<Transfer> transfers) {
    public record Transfer(String kind, long id, long amount, String status, String memo,
            String bankBin, String bankName, String accountNumber, String accountHolder,
            String qrImageUrl) { }
}
