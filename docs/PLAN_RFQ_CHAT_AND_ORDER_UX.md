# Kế hoạch RFQ, chat và giao diện đơn hàng

Ngày: 10/11/2026. Trạng thái: đã triển khai. Kết quả, hướng dẫn chạy và giới hạn kiểm chứng: [IMPLEMENTATION_RFQ_CHAT_ORDER_UX.md](IMPLEMENTATION_RFQ_CHAT_ORDER_UX.md).

## 1. Nền code sau merge

- Backend đã merge `origin/feature/identity-service` vào `main` local.
- Frontend đã merge nhánh tương ứng `origin/feature/indentity-fe` để đồng bộ khôi phục mật khẩu bằng OTP.
- Giữ service interface + implementation; giữ xác minh email bằng liên kết đang được FE hỗ trợ.
- OTP đặt lại mật khẩu gắn với email, hết hạn theo cấu hình, dùng một lần, vô hiệu sau 5 lần nhập sai. Flyway V8 bổ sung bộ đếm số lần sai.
- Các thay đổi local trước merge đã được khôi phục; có backup branch và ref `refs/codex/backups/pre-identity-20261110` ở cả hai repository. Stash tạm của lần merge này đã được dọn sau khi khôi phục.
- Giữ 4 business services + API Gateway. Tận dụng Express server hiện có cho Gemini; không tạo business service thứ năm.

## 2. Điều chỉnh giao diện cơ bản

- Bỏ nút `Xem sản phẩm` ở phần đầu trang chi tiết gian hàng; giữ danh sách sản phẩm phía dưới.
- Đổi nhãn quay về từ Admin/Seller thành `Quay lại gian hàng`. Giữ hành vi quay về khu vực mua sắm hiện tại; không tự chọn một shop khi admin quản lý nhiều shop.
- Nút `Thoát` của Seller hiện cũng gọi `onBackToShop`, trùng với nút quay lại. Bỏ nút trùng, không biến nó thành đăng xuất ngoài ý muốn.
- Sidebar có vùng nội dung cuộn riêng và chân sidebar luôn nhìn thấy; kiểm tra cả màn hình thấp và thiết bị di động.
- Nút `Thanh toán PayOS` dùng cùng kiểu button có nền/viền với các thao tác thanh toán khác; giữ trạng thái đang tải và chống bấm lặp.

## 3. Phân quyền khu vực yêu cầu báo giá

- Customer: tab `Yêu cầu báo giá của tôi`, tạo và theo dõi yêu cầu của mình. Không thấy sàn yêu cầu báo giá hoặc tab gửi báo giá.
- Supplier: tab `Sàn yêu cầu báo giá` và `Báo giá của tôi`; không thấy tab yêu cầu của người mua. Supplier chưa được xác minh thấy thông báo giới hạn; không được gửi báo giá.
- Nếu tài khoản đồng thời có hồ sơ customer và supplier, khu vực B2B dùng chế độ supplier. Quyền mua lẻ ở phần mua sắm vẫn theo quyền hiện có.
- Tin nhắn dùng widget chung, không phụ thuộc việc đang mở tab RFQ nào.
- Hiện tại FE chỉ lọc tab báo giá theo trạng thái supplier, còn feed và mine đều hiện. BE đang mô tả feed mặc định là public: phải cập nhật kiểm tra quyền tại controller/application service và tài liệu API theo yêu cầu mới.
- `GET /api/rfqs?mine=true`: chỉ lấy customer hiện tại từ xác thực, không nhận customerId làm quyền truy cập. Chặn truy cập trái chế độ B2B đã quy định.
- Feed và chi tiết: supplier chỉ thấy yêu cầu public đủ điều kiện hoặc yêu cầu mời đúng mình; customer chỉ đọc yêu cầu của mình. Giữ bảo vệ địa chỉ, thông tin riêng và báo giá của nhà cung cấp khác.
- Reset tab hợp lệ sau đăng nhập, đổi tài khoản hoặc mở URL cũ; ẩn UI không thay thế kiểm tra quyền tại BE.
- Rà các caller hiện có, kể cả mobile đang nằm trong repository FE, trước khi đổi contract public feed.

## 4. Form báo giá và hiển thị phiên bản

- Chọn sản phẩm chỉ trong gian hàng của supplier đang đăng nhập; BE tiếp tục kiểm tra quyền sở hữu và trạng thái sản phẩm/biến thể.
- Khi chưa chọn biến thể, hiện `Chọn biến thể sản phẩm`; có trạng thái đang tải, không có biến thể và tải lỗi.
- Khi chọn biến thể, điền ngay tên, SKU và giá niêm yết từ Catalog; khi đổi biến thể phải cập nhật các giá trị này.
- Đề xuất về giá: hiển thị `Giá niêm yết` chỉ đọc; tự điền `Đơn giá báo giá` bằng giá đó nhưng vẫn cho thương lượng. RFQ hiện cho phép giá thỏa thuận, nên khóa đơn giá bằng giá bán lẻ sẽ thay đổi nghiệp vụ. Chưa khóa giá báo giá trong kế hoạch này.
- Giữ kiểm tra tổng tiền, giảm giá, số lượng, tiền cọc và phiên bản tại server; không lấy tổng do FE tính làm số tiền chính thức.
- Chỉ hiển thị một header đầy đủ gồm nhà cung cấp, phiên bản và trạng thái cho báo giá đang xem. Nếu có nhiều báo giá/phiên bản, danh sách chọn dùng tóm tắt gọn; không lặp hai thẻ đầy đủ như ảnh.
- Không thay đổi lịch sử phiên bản, điều kiện chấp nhận báo giá hoặc idempotency của tạo đơn.

## 5. Upload ảnh biến thể tại Catalog

- Bổ sung endpoint dự kiến `POST /api/products/{productId}/images`, nhận `multipart/form-data`, trả URL ảnh và thông tin cần để preview.
- Controller mỏng, service interface + implementation xử lý lưu file; kiểm tra supplier sở hữu sản phẩm trước khi ghi file.
- Lưu dưới thư mục upload cấu hình ngoài source/target; sinh tên file ở server, chặn path traversal, giới hạn dung lượng và xác minh định dạng ảnh thực tế. Không nhận SVG hoặc nội dung thực thi.
- Public URL qua Gateway; quy định rõ đường dẫn upload do Catalog phục vụ, không nhầm với `/images/demo/` do FE phục vụ.
- FE cho chọn file, xem preview, hiển thị tiến trình/lỗi và dùng URL trả về khi lưu biến thể. Giữ lựa chọn nhập URL hiện có.
- Local lưu trên ổ đĩa BE. Khi chạy container phải mount thư mục này để ảnh không mất khi tạo lại container.

## 6. Ảnh sản phẩm trong đơn RFQ

- Lỗi hiện tại: `QuotationApplicationServiceImpl` tạo item gửi Commerce với `imageUrlSnapshot = null`; Commerce đã có chỗ lưu snapshot ảnh nhưng không nhận được ảnh.
- Chốt snapshot ảnh Catalog tại lúc lưu phiên bản báo giá cho item có variant; bổ sung trường/migration RFQ nếu cần. Catalog là nguồn ảnh, FE không được gửi ảnh tùy ý thay cho variant đã chọn.
- Khi chấp nhận báo giá, chuyển ảnh trong snapshot đã chốt sang Commerce. Retry phải dùng cùng snapshot, không tự thay bằng ảnh mới làm thay đổi fingerprint của yêu cầu tạo đơn.
- Hàng đặt riêng không có variant dùng một ảnh minh họa chung cố định. FE có fallback nếu ảnh null, URL lỗi hoặc file không còn.
- Đơn/báo giá cũ thiếu ảnh: ưu tiên snapshot hiện có; có thể bổ sung ảnh hiển thị bằng batch tra variant qua API có quyền phù hợp. Không sửa ngược giá, tên hoặc số lượng trong lịch sử.
- Phân biệt ảnh fallback hiển thị và dữ liệu snapshot; không ghi đè lịch sử đơn cũ bằng dữ liệu Catalog hiện tại.

## 7. Tách danh sách và chi tiết đơn hàng

- `/orders`: danh sách gọn gồm mã đơn, ngày đặt, trạng thái, nhà bán, tổng tiền và `Xem chi tiết`; bỏ accordion.
- `/orders/:orderId`: trang chi tiết riêng, có `Quay lại danh sách đơn hàng` ở vị trí rõ ràng.
- Tái sử dụng phần chi tiết hiện có: địa chỉ snapshot, từng đơn nhà bán, sản phẩm, thanh toán, vận chuyển, hủy, khiếu nại và đánh giá. Giữ đúng điều kiện bật/tắt từng thao tác.
- Bổ sung route chi tiết vào router hiện có; điều hướng mở đơn dùng push. Back/Forward và reload URL phải hoạt động.
- Giữ bộ lọc, trang và vị trí cuộn của danh sách khi quay lại; link mở trực tiếp vẫn có đường quay về `/orders`.
- API chi tiết phải kiểm tra chủ đơn tại server. Không tải toàn bộ đơn rồi lọc bằng FE để bảo vệ dữ liệu.
- PayOS return/cancel trở về đúng đơn và đối soát bằng API; query trên URL không tự xác nhận đã trả tiền hoặc đã hủy đơn.

## 8. Widget tin nhắn người mua–nhà cung cấp

- Icon tin nhắn mở widget nổi: danh sách đối tác/hội thoại, thông báo chưa đọc, chọn hội thoại rồi mở nội dung; có quay lại, thu nhỏ và đóng.
- Dùng lại `conversationApi`, `ConversationsView`, `ChatPanel`, `useChatThread` và cơ chế kết nối hiện có; không tạo hệ thống nhắn tin thứ hai.
- Danh sách có phân trang/tải thêm, loading, trạng thái rỗng và thử lại. Lấy toàn bộ hội thoại của tài khoản qua các trang API, không giới hạn âm thầm ở 20 mục đầu.
- Hội thoại hiện gắn với RFQ: có thể nhóm theo đối tác nhưng phải giữ từng RFQ là thread riêng, không trộn lịch sử giữa các RFQ.
- Tên/ảnh đối tác lấy từ dữ liệu có quyền đọc; kiểm tra membership ở cả REST và kết nối realtime. Đổi tài khoản hoặc đăng xuất phải hủy kết nối và xóa dữ liệu widget cũ.
- Mở chat từ báo giá sẽ chọn đúng hội thoại trong widget. Widget dùng chung trên các trang, có bố cục gọn trên mobile.

## 9. Trợ lý Gemini có truy xuất dữ liệu

- Widget AI tách biệt với tin nhắn giữa người dùng. Model dự kiến: `gemini-3.1-flash-lite`, theo [tài liệu Google](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite).
- FE gọi endpoint server hiện có; bổ sung endpoint chat và giữ cấu hình phía server trong env. Không gọi Gemini trực tiếp từ trình duyệt.
- RAG: truy xuất thông tin sản phẩm/biến thể, giá, tồn có thể bán, khuyến mãi và gian hàng từ Catalog qua API; kết hợp tài liệu chính sách đã được duyệt cho khách hàng.
- Dùng truy xuất theo từ khóa/thuộc tính và giới hạn tập ngữ cảnh trước; chưa cần thêm vector database hoặc Redis cho quy mô hiện tại.
- Chính sách phải được đối chiếu code/tài liệu nghiệp vụ rồi mới đưa vào nguồn trả lời. Không đưa hướng dẫn kỹ thuật nội bộ hoặc dữ liệu cá nhân vào ngữ cảnh.
- Câu trả lời gắn link sản phẩm/gian hàng hoặc nguồn chính sách tương ứng; không tự bịa giá, tồn kho, ưu đãi, phí hay điều kiện hoàn tiền.
- Khi không có nguồn phù hợp hoặc câu hỏi ngoài phạm vi sàn, trả lời ngắn rằng chưa hỗ trợ/không đủ thông tin và hướng dẫn tới chức năng phù hợp.
- Phân tách system instruction và nội dung truy xuất; coi mô tả sản phẩm/tin nhắn là dữ liệu không đáng tin. Giới hạn input, lịch sử, số request, thời gian chờ và dữ liệu đầu ra.
- Bản đầu chỉ tư vấn bằng dữ liệu public; không tự đặt đơn, đổi giá, duyệt khiếu nại hoặc truy cập đơn/tài khoản của người khác.

## 10. Thứ tự thực hiện và nghiệm thu

1. Sửa UI nhỏ, role/tab B2B và form báo giá; bổ sung test quyền tại BE.
2. Hoàn thiện upload ảnh và snapshot ảnh RFQ; kiểm tra qua Gateway.
3. Tách trang danh sách/chi tiết đơn; kiểm tra lại thanh toán và thao tác hậu mãi.
4. Đưa chat hiện có vào widget chung, kiểm tra hai tài khoản ở hai trình duyệt.
5. Chuẩn hóa nguồn chính sách, triển khai trợ lý RAG và kiểm thử câu hỏi ngoài phạm vi.

Kiểm tra bắt buộc:

- Customer, supplier đã/chưa xác minh, tài khoản có hai vai trò, guest và admin; gọi thẳng API không vượt quyền.
- Báo giá catalog/đặt riêng, nhiều biến thể, sửa phiên bản, retry chấp nhận báo giá; ảnh snapshot không đổi khi retry.
- Upload hợp lệ/sai định dạng/quá lớn, URL ảnh qua Gateway và restart BE vẫn xem được ảnh đã lưu.
- Đơn mua lẻ/RFQ: mở chi tiết, tải lại, Back/Forward, lọc danh sách, PayOS return/cancel; không thay đổi số tiền hoặc trạng thái bằng dữ liệu FE.
- Chat hai người, nhiều RFQ với cùng đối tác, unread, mất kết nối, đổi tài khoản và thu nhỏ widget.
- AI trả lời theo Catalog/chính sách, không có dữ liệu, dữ liệu cũ, lỗi model, câu hỏi ngoài phạm vi và nội dung cố ghi đè chỉ dẫn.
- TypeScript, regression FE, production build và test BE của các service bị thay đổi. Cập nhật tài liệu API và hướng dẫn chạy sau khi triển khai.
