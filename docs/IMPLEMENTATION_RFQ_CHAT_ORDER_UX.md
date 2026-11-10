# RFQ, chat, upload ảnh và đơn hàng

## Đã triển khai

| Phần | Kết quả |
|---|---|
| Gian hàng | Bỏ nút xem sản phẩm ở hero; giữ danh sách sản phẩm |
| Admin/Seller | Nhãn quay lại là `Quay lại gian hàng`; bỏ nút Thoát trùng hành động; chân sidebar không bị cuộn khuất |
| RFQ | Customer chỉ có yêu cầu của mình; supplier có sàn yêu cầu và báo giá đã gửi. BE kiểm tra cùng quy tắc, không chỉ ẩn tab |
| Báo giá | Chọn biến thể có hướng dẫn rõ; tự điền giá Catalog, cho sửa đơn giá thỏa thuận, giữ giá niêm yết tham chiếu; thu gọn phần chọn phiên bản |
| Ảnh | Catalog nhận upload local; RFQ lưu ảnh theo phiên bản báo giá và gửi snapshot sang Commerce; hàng đặt riêng dùng ảnh chung |
| Đơn hàng | `/orders` dùng API tóm tắt; `/orders/{id}` tải chi tiết có kiểm tra chủ đơn. Back/Forward giữ bộ lọc và vị trí cuộn |
| Thanh toán | Nút PayOS rõ ràng; thông tin quay về gắn đúng lần thanh toán. Redirect không tự xác nhận tiền; dùng đối soát hiện có |
| Chat đối tác | Widget danh sách hội thoại, phân trang, chọn thread theo RFQ, quay lại, thu nhỏ, đóng và unread. Dùng API/STOMP hiện có |
| Trợ lý AI | Widget riêng, truy xuất Catalog và chính sách đã đối chiếu code; câu trả lời có nguồn; chặn câu ngoài phạm vi và nguồn không hợp lệ |

Không đổi số service, không thêm message broker và không đổi quy tắc tính tiền đơn hàng.

## Contract mới/bổ sung

- `POST /api/products/{productId}/images`: Bearer của supplier đã xác minh và sở hữu sản phẩm; `multipart/form-data`, trường `file`. JPEG/PNG thực, tối đa 5 MiB và 4096 px mỗi chiều; trả 201 với `{url,contentType,width,height,size}`.
- `GET /uploads/catalog/{filename}`: public qua Gateway; chỉ phục vụ tên file do server sinh. URL upload là URL tuyệt đối của Gateway, không nhầm ảnh `/images/` do FE phục vụ.
- `GET /api/rfqs`: feed chỉ cho supplier đăng nhập. `mine=true` dành cho chế độ customer. Nếu có cả hai hồ sơ, B2B dùng chế độ supplier. Supplier chưa xác minh chỉ đọc, chưa được gửi báo giá.
- `GET /api/rfqs/{id}`: chủ yêu cầu hoặc supplier đủ quyền; giữ bảo vệ thông tin riêng và giới hạn lời mời.
- `GET /api/orders`: thêm `sellerOrderCount`, `itemCount` (số dòng hàng), `sellerOrderStatuses`, `hasCancellation`; giữ pagination. Các dữ liệu tổng hợp được lấy theo batch trong Commerce.
- `GET /api/orders/{id}`: item thêm `imageUrl` phục vụ hiển thị. `imageUrlSnapshot` vẫn là lịch sử bất biến; ảnh thiếu được tra Catalog theo batch và kiểm tra variant/store. Catalog lỗi không làm mất khả năng xem đơn.
- Quotation item thêm `imageUrlSnapshot`; được lưu khi tạo phiên bản báo giá. Chấp nhận/retry dùng lại snapshot đó, không lấy giá hoặc ảnh mới để sửa nội dung đã chốt.
- Express của FE thêm `POST /api/gemini/chat` với `{message,history}`; trả `{answer,sources:[{id,title,url}]}`. Đây không phải business service mới. Endpoint gợi ý cũ dùng cùng luồng truy xuất.

## Trợ lý AI

- Model `gemini-3.1-flash-lite`; server gọi model, trình duyệt chỉ gọi endpoint chat.
- Nguồn sản phẩm: public API của Catalog qua Gateway, gồm biến thể, giá, tồn có thể bán, gian hàng và khuyến mãi.
- Nguồn chính sách: `MoonCakeMarketFrontend/server/marketplacePolicies.ts`, đối chiếu checkout, RFQ, tài chính và vận chuyển hiện tại. Khi đổi nghiệp vụ phải cập nhật và review cùng nguồn này.
- Chỉ lấy nguồn liên quan; không có dữ liệu thì báo rõ, không thêm sản phẩm mẫu. Câu ngắn ngoài lề không được tự kế thừa phạm vi từ câu trước.
- Giá/phí hiển thị được server lấy đúng từng trường Catalog và định dạng. Nội dung model tự viết số tiền bị bỏ; không lấy một giá trị phí vận chuyển để xác nhận giá sản phẩm.
- Giới hạn câu hỏi 1.000 ký tự, lịch sử gửi lên tối đa 6 tin, 10 request/phút/IP trên một process; truy xuất và model có timeout.
- Nếu model trả 503 hoặc hết thời gian chờ, UI báo lỗi và giữ câu hỏi để thử lại; không dùng dữ liệu mẫu thay thế.
- Trợ lý chỉ đọc dữ liệu public, không đặt đơn, sửa hồ sơ hay phán quyết khiếu nại. Nguồn và lịch sử được xem là dữ liệu, không phải lệnh thực thi.

## Chạy trên máy

1. Giữ bốn database hiện có; không reset DB và không chạy lại seed cho thay đổi này.
2. Rebuild backend bằng Maven để cập nhật class và resources, rồi restart Identity nếu chưa chạy bản merge, Gateway, Catalog, Commerce và RFQ trong IntelliJ.
3. RFQ tự chạy Flyway **V11** để thêm `quotation_item.image_url_snapshot`. Đơn và báo giá cũ không bị sửa ngược snapshot. Identity **V8** thuộc lần merge trước, thêm bộ đếm nhập sai OTP nếu chưa chạy.
4. FE chạy `npm run dev` từ thư mục `MoonCakeMarketFrontend`; mở `http://localhost:3000`.
5. Upload ảnh cần quyền ghi thư mục `CATALOG_UPLOAD_DIR`; mặc định `${user.home}/.mooncake/uploads/catalog`. `CATALOG_PUBLIC_BASE_URL` mặc định `http://localhost:8080`. Docker Compose đã mount volume riêng để giữ ảnh qua lần tạo lại container.

Không xóa thư mục upload khi clean/build source. Xem thêm [Catalog IMAGE_UPLOAD.md](../CatalogStoreService/IMAGE_UPLOAD.md).

## Kiểm chứng

- Commerce và RFQ: toàn bộ suite chạy qua. Catalog và Gateway: suite chạy qua, gồm kiểm tra WebSocket và upload.
- FE: TypeScript, production build, regression đơn hàng/thanh toán, test quyền/form RFQ, test chat, upload và trợ lý.
- Trình duyệt: mở danh sách/chi tiết, Back/Forward giữ filter; chat mở ngay trên trang; ảnh hàng đặt riêng; mobile 390 px không tràn ngang sau khi thu gọn logo navbar.
- Gọi Gemini thật đã trả lời chính sách có nguồn và gợi ý sản phẩm với giá/tồn từ Catalog thật.
- SQL Server local: DDL V11 đã được kiểm tra trong transaction và rollback; việc áp dụng chính thức do Flyway khi restart RFQ.
- Không thực hiện thanh toán, hủy đơn hoặc chuyển tiền thật trong lần kiểm chứng này. Kiểm tra giao diện đơn/chat dùng fixture trình duyệt; truyền tin realtime được kiểm tra bằng test backend hiện có. Mobile đã cập nhật caller RFQ customer, chưa chạy full Flutter suite.

FE có thể chạy toàn bộ kiểm tra liên quan bằng `npm run test:ux`. Test upload cần cho phép HTTP loopback. Trong môi trường Windows có đường dẫn temp dài, test Gateway dùng JVM property `-Djdk.net.unixdomain.tmpdir=<thư mục ngắn>`; đây là cấu hình chạy test, không phải thay đổi nghiệp vụ.

## Test thủ công

1. Đăng nhập customer, vào B2B: chỉ thấy `Yêu cầu báo giá của tôi`; supplier thấy feed và báo giá của mình.
2. Supplier mở sản phẩm đã tạo, thêm biến thể và upload JPEG/PNG; kiểm tra preview, lưu và xem ảnh qua Gateway.
3. Gửi báo giá có sản phẩm Catalog: giá tự điền nhưng sửa được; chọn lại biến thể thì cập nhật giá. Chấp nhận báo giá và xem ảnh trong đơn vừa tạo.
4. Mở `/orders`, chọn bộ lọc, mở chi tiết rồi Back/Forward/reload. Thử các thao tác được server cho phép theo trạng thái đơn.
5. Dùng hai tài khoản ở hai trình duyệt: mở icon tin nhắn, chọn đúng hội thoại, gửi/nhận, thu nhỏ/mở lại và kiểm tra chưa đọc.
6. Hỏi trợ lý về sản phẩm, khuyến mãi và chính sách; bấm nguồn để mở đúng sản phẩm/gian hàng. Hỏi ngoài phạm vi phải nhận phản hồi giới hạn hỗ trợ.
