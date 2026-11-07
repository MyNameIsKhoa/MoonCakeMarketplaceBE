# Luồng nghiệp vụ và quy tắc TV1–TV6

FE gọi Gateway. Identity quản lý tài khoản và hồ sơ, Catalog quản lý gian hàng và tồn kho, RFQ quản lý yêu cầu và báo giá, Commerce quản lý đơn, tiền và giao hàng. TV3, TV5 và TV6 phối hợp qua Java service trong Commerce. Các service khác trao đổi qua REST/OpenFeign.

| Phụ trách | Luồng chính | Quy tắc |
|---|---|---|
| TV1 | Đăng ký → đăng nhập → lưu địa chỉ → đăng ký nhà bán → admin duyệt → quản lý gian hàng | Phân biệt userId, customerId và supplierId. Chỉ chủ tài nguyên hoặc admin theo quyền của endpoint được thao tác. |
| TV2 | Công bố sản phẩm, biến thể, khuyến mãi và giữ kho | Checkout dùng giá và tồn kho từ Catalog. Giữ kho 15 phút. Áp BUY_X_GET_Y tốt nhất trước, sau đó giảm tiền cố định rồi cộng tỷ lệ giảm phần trăm. |
| TV3 | Giỏ hàng → checkout → một đơn tổng và một đơn con mỗi gian hàng → lịch sử | Snapshot địa chỉ, sản phẩm, giá và phí giao theo store. Dùng lại Idempotency-Key cho cùng yêu cầu trong 30 phút. |
| TV4 | RFQ → báo giá và phiên bản → khách chấp nhận → Commerce tạo đơn | Chuyển snapshot báo giá đã chấp nhận. Retry giữ cùng snapshot/key. Mỗi báo giá được chuyển thành đơn tối đa một lần. |
| TV5 | Tạo link PayOS → webhook/đối soát → ghi nhận tiền → giải ngân hoặc xử lý ngoại lệ | Redirect không xác nhận tiền. FULL hoặc DEPOSIT/REMAINING theo đơn. REMAINING đến hạn lúc 00:00 giờ Việt Nam, trước ngày giao 3 ngày. |
| TV6 | Chuẩn bị → giao → xác nhận đã giao → khách nghiệm thu → đánh giá | Nhà bán VERIFIED quản lý shipment của mình. Khách nghiệm thu đơn thuộc mình. |

## Tiền và giao hàng

- **Đủ cọc được chuẩn bị hàng. Đủ tổng tiền mới được chuyển shipment sang SHIPPED.** Đơn FULL cần thu đủ tiền để bắt đầu chuẩn bị.
- Đơn con chờ thanh toán chuyển sang READY_FOR_FULFILLMENT khi đạt ngưỡng chuẩn bị. Thu thêm tiền hoặc nhận callback lặp giữ nguyên trạng thái đã tiến xa hơn.
- Shipment đi theo PLANNED → SHIPPED → DELIVERED. Nếu đơn con có nhiều shipment, đơn chỉ chuyển DELIVERED sau khi tất cả đã giao.
- Nghiệm thu lưu thời điểm lần đầu. Commerce cập nhật dữ liệu giao hàng/nghiệm thu cho finance cùng transaction. Giải ngân cần đủ tiền, đã giao, khách đã nghiệm thu, cấu hình phí hợp lệ và không có hồ sơ tài chính chặn.
- Đơn tổng hiển thị bước chậm nhất của các đơn con còn hoạt động. DISPUTED được ưu tiên. Nếu tất cả đơn con đã hủy, đơn tổng chuyển CANCELLED. Intent CANCEL_REQUESTED được giữ riêng trong lúc xử lý.

## Hủy đơn và đối soát

- Hủy đơn mua lẻ chưa trả tiền lưu yêu cầu trước và chặn tạo link mới. Xác minh link với PayOS trước khi hoàn kho. Thời điểm hết hạn local không đủ để kết luận chưa thu tiền.
- Khi kết quả PayOS chưa rõ, giữ yêu cầu để đối soát. Nếu mất phản hồi hoàn kho, thử lại cùng reservation key. Catalog xử lý lặp mà không cộng kho lần hai.
- Đơn mua lẻ cũ thiếu `stock_reservation_key` bị chặn hủy tự động với `STOCK_RESERVATION_RECONCILIATION_REQUIRED`. Không thể suy ra mã giữ kho sau khi `checkout_attempt` đã bị xóa; cần đối soát tồn kho và xử lý thủ công theo hồ sơ đơn, không sửa trực tiếp trạng thái đơn trong SQL.

```sql
SELECT order_id, order_no, status
FROM mss_commerce.dbo.customer_order
WHERE source_type = 'RETAIL'
  AND stock_reservation_key IS NULL
  AND status IN ('AWAITING_PAYMENT', 'PENDING_CONFIRMATION', 'CANCEL_REQUESTED');
```
- Nếu PayOS báo đã thu, ghi nhận tiền và chặn hủy theo luồng chưa thanh toán. Tiền đến sau khi đơn đã hủy cần xử lý tài chính riêng, không tự mở lại đơn.
- RECONCILING là trạng thái cần xác minh giao dịch. Không sửa status hoặc mã PayOS trực tiếp trong SQL.

## Kiểm thử bàn giao

1. Dùng JDK 21. Khởi động Commerce để Flyway áp dụng V8 lưu accepted_at và V9 lưu mã giữ kho trên đơn. V9 khôi phục mã cho các đơn cũ còn checkout attempt. Các đơn cũ đã mất attempt cần kiểm tra tồn kho riêng khi hủy. Đơn đã nghiệm thu từ trước có thể có accepted_at rỗng.
2. Dùng hai nhà bán đã duyệt. Checkout hai gian hàng, lặp cùng key và kiểm tra số đơn, snapshot, tồn kho.
3. Chạy đơn mua lẻ FULL và đơn RFQ có cọc. Kiểm tra tạo shipment sau khi thu cọc, ship bị chặn trước khi thu phần còn lại, rồi giao và nghiệm thu.
4. Kiểm tra đơn của người khác bị từ chối. Kiểm tra callback lặp, PayOS mất kết nối và webhook đến chậm khi yêu cầu hủy.
5. Tắt Catalog API và kiểm tra FE báo lỗi/thử lại. Yêu thích lưu riêng theo tài khoản trên trình duyệt, chưa đồng bộ giữa thiết bị.

Các quy tắc chi tiết và API được mô tả trong [API specification](API_SPECIFICATION.md), [internal contracts](contracts/README.md) và [tích hợp finance](TV5_FINANCE_INTEGRATION.md).
