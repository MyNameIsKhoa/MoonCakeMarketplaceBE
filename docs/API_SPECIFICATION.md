# ĐẶC TẢ API — MSS MOONCAKE MARKETPLACE

**Phiên bản tài liệu:** 1.1
**Ngày cập nhật:** 20/10/2026
**Mục đích:** Trình bày thiết kế API để kiểm tra chức năng, phân quyền và giao tiếp giữa các dịch vụ.

## 1. Phạm vi

Hệ thống gồm 4 dịch vụ nghiệp vụ và 1 API Gateway. Đây là **danh mục API mục tiêu**, không phải danh sách toàn bộ endpoint hiện chạy. Dấu `*` nghĩa là còn chính sách/phương thức tích hợp cần chốt; **không có dấu `*` cũng không đồng nghĩa đã triển khai**. Đối chiếu trạng thái mã nguồn hiện tại ở mục 1.1 trước khi dùng tài liệu để tích hợp frontend.

| Thành phần | Cổng | Phạm vi | Phụ trách |
|---|---:|---|---|
| API Gateway | 8080 | Điểm truy cập API của ứng dụng web | Nhóm hạ tầng |
| Identity Service | 8081 | Tài khoản, hồ sơ khách hàng, địa chỉ, hồ sơ nhà cung cấp | TV1 |
| Catalog & Store Service | 8082 | Cửa hàng, danh mục, sản phẩm, biến thể, khuyến mãi | TV1, TV2 |
| Commerce Service | 8083 | Giỏ hàng, đơn hàng, thanh toán, giao hàng, hoàn tiền, đối soát, đánh giá | TV3, TV5, TV6 |
| RFQ & Negotiation Service | 8084 | Yêu cầu báo giá, báo giá, thương lượng và tin nhắn | TV4 |

Mỗi dịch vụ sở hữu cơ sở dữ liệu riêng. Giao tiếp đồng bộ giữa dịch vụ sử dụng REST/OpenFeign. Các chức năng trong cùng Commerce Service gọi lớp service nội bộ.

### 1.1. Trạng thái triển khai đối chiếu mã nguồn

| Dịch vụ | Public API có controller hiện tại | API còn ở mức thiết kế hoặc skeleton |
|---|---|---|
| Identity | ID01–ID12, AD03–AD04 và lịch sử xác minh supplier | AD01–AD02, AU01–AU05 |
| Catalog & Store | ST01–ST04; CA01–CA04, CA06–CA09, CA11–CA14, CA16–CA20 | Thao tác xóa CA05, CA10, CA15, CA21 (chờ chốt chính sách) và kiểm duyệt AD05–AD06 trong mục 4 |
| Commerce | OR01–OR06, OR08–OR11 đã có controller/backend đọc-ghi cơ bản | OR07, OR12 và các API TV5/TV6 trong mục 5 chưa triển khai đầy đủ |
| RFQ & Negotiation | `POST /api/quotations/{quotationId}/accept` nhận version/addressId và buyer principal, nhưng conversion vẫn trả 501 | Các API RFQ, báo giá còn lại và hội thoại/WebSocket trong mục 6 |

“Có controller” chỉ xác nhận route đã khai báo, **không xác nhận nghiệp vụ đã hoàn chỉnh hoặc sẵn sàng production**. `GET /api/cart` chỉ trả ID/quantity; `POST /api/cart/pricing` gọi Catalog IN07/IN08 để trả giá/tổng theo store nhưng không giữ kho hay tạo đơn. Order read hiện chưa có dữ liệu từ checkout thực tế. Các API nội bộ đã có controller gồm IN01–IN09, IN11 và IN12; IN10 chưa có. Gateway đã định tuyến `/api/seller-orders/**` về Commerce. Trạng thái này là ảnh chụp mã nguồn ngày cập nhật; khi triển khai thêm phải cập nhật tài liệu cùng contract và test.

## 2. Quy ước API

- Địa chỉ phát triển: `http://localhost:8080`. Khi triển khai sử dụng HTTPS.
- API của ứng dụng web bắt đầu bằng `/api`; API nội bộ bắt đầu bằng `/internal/v1` và không công khai qua Gateway.
- API có yêu cầu đăng nhập sử dụng `Authorization: Bearer <accessToken>`. API nội bộ và callback của đối tác sử dụng cơ chế xác thực riêng phù hợp.
- Tên tài nguyên dùng danh từ số nhiều; trường dữ liệu dùng `camelCase`. Giữ các đường dẫn đơn thể hiện có: `/api/cart`, `/api/supplier-profile`.
- `GET`: đọc; `POST`: tạo hoặc thực hiện nghiệp vụ; `PUT`: thay thế toàn bộ; `PATCH`: cập nhật một phần; `DELETE`: xóa theo chính sách dữ liệu.
- Request hiện có dùng `application/json`. `application/merge-patch+json` là quy ước **đề xuất**, chưa được áp dụng đồng bộ cho các PATCH đã viết; không gửi kiểu này cho endpoint hiện hữu khi chưa kiểm tra OpenAPI của service.
- ID dạng chuỗi và tiền dạng chuỗi là **contract mục tiêu**; DTO hiện tại của Identity/Catalog còn dùng ID số (`Long`). Frontend phải theo response thực tế cho tới khi có thay đổi contract được version hóa/phối hợp.
- Ngày mục tiêu dùng `YYYY-MM-DD`, thời điểm dùng ISO-8601 UTC. Một số DTO hiện dùng `LocalDateTime`; không mặc định coi response đó đã mang ký hiệu múi giờ `Z`.
- Danh sách lớn **thiết kế** dùng `page`, `size`, `sort`, trả `items`, `page`, `size`, `totalElements`, `totalPages`; response hiện tại chưa thống nhất: ví dụ danh sách địa chỉ/cửa hàng trả mảng, còn danh sách supplier chờ xác minh trả Spring `Page`.
- Client gửi `Idempotency-Key` cho checkout, chấp nhận báo giá và các lệnh có nguy cơ tạo giao dịch trùng. Cùng khóa và dữ liệu phải trả cùng kết quả nghiệp vụ.

Các quy ước định dạng mới cần được cập nhật đồng thời ở backend, frontend và các dịch vụ sử dụng API trước khi áp dụng vào endpoint hiện có.

### 2.1. Quyền truy cập

| Quyền | Phạm vi |
|---|---|
| Công khai | Dữ liệu đã được công bố; không bao gồm thông tin cá nhân hoặc giao dịch riêng. |
| Người dùng | Tài khoản đã xác thực, truy cập dữ liệu của chính mình. |
| Khách hàng | Giỏ hàng, yêu cầu báo giá, đơn hàng và thanh toán thuộc khách hàng. |
| Nhà cung cấp | Cửa hàng, sản phẩm, báo giá và phần đơn hàng thuộc nhà cung cấp. |
| Bên tham gia | Khách hàng hoặc nhà cung cấp của giao dịch/hội thoại tương ứng. |
| Quản trị | Quyền quản lý được cấp riêng cho từng nghiệp vụ. |
| Đối tác | Hệ thống thanh toán hoặc vận chuyển đã được xác thực. |
| Nội bộ | Dịch vụ được phép sử dụng API nội bộ. |

Server xác định người thực hiện từ thông tin xác thực và kiểm tra quyền sở hữu dữ liệu. ID trong request không thay thế việc kiểm tra quyền.

### 2.2. Mã phản hồi

| HTTP | Ý nghĩa |
|---:|---|
| 200 | Đọc, cập nhật hoặc thực hiện nghiệp vụ thành công. |
| 201 | Tạo mới thành công; trả định danh và địa chỉ tài nguyên khi phù hợp. |
| 202 | Đã tiếp nhận, đang xử lý; có cơ chế tra cứu kết quả. |
| 204 | Thành công, không có nội dung phản hồi. |
| 400 | Dữ liệu hoặc tham số không hợp lệ. |
| 401 / 403 | Chưa xác thực hoặc không đủ quyền. |
| 404 | Không tìm thấy tài nguyên hoặc không được phép xem tài nguyên riêng. |
| 409 | Trùng dữ liệu, sai trạng thái hoặc xung đột giao dịch. |
| 412 / 415 | Điều kiện cập nhật không còn đúng hoặc kiểu nội dung không được hỗ trợ. |
| 429 | Vượt giới hạn yêu cầu. |
| 500 / 502 / 503 / 504 | Lỗi xử lý, lỗi phản hồi dịch vụ liên quan, dịch vụ không sẵn sàng hoặc hết thời gian chờ. |

Kết quả thành công hiện trả dữ liệu trực tiếp. `application/problem+json` với `type`, `title`, `status`, `detail`, `instance`, `code`, `traceId` là **mục tiêu**, chưa phải lỗi trả về thống nhất của hệ thống. Identity hiện trả `ErrorResponse` JSON gồm `timestamp`, `status`, `error`, `message`, `path`, `validationErrors` (khi có); callback tương lai phải theo hợp đồng của đối tác.

## 3. Identity Service

### 3.1. Tài khoản và địa chỉ

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| ID01 | `POST /api/auth/register` | Đăng ký tài khoản | Công khai |
| ID02 | `POST /api/auth/login` | Đăng nhập, nhận access token | Công khai |
| ID03 | `GET /api/users/me` | Xem hồ sơ cá nhân | Người dùng |
| ID04 | `PATCH /api/users/me` | Cập nhật hồ sơ cá nhân | Người dùng |
| ID05 | `GET /api/addresses` | Xem danh sách địa chỉ | Khách hàng |
| ID06 | `GET /api/addresses/{addressId}` | Xem chi tiết địa chỉ | Khách hàng |
| ID07 | `POST /api/addresses` | Thêm địa chỉ | Khách hàng |
| ID08 | `PATCH /api/addresses/{addressId}` | Cập nhật địa chỉ, lựa chọn mặc định | Khách hàng |
| ID09 | `DELETE /api/addresses/{addressId}` | Xóa địa chỉ đã lưu | Khách hàng |

### 3.2. Hồ sơ nhà cung cấp và quản trị

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| ID10 | `POST /api/supplier-profile` | Đăng ký hồ sơ nhà cung cấp | Người dùng |
| ID11 | `GET /api/supplier-profile` | Xem hồ sơ nhà cung cấp của tài khoản | Người dùng |
| ID12 | `PATCH /api/supplier-profile` | Cập nhật hồ sơ nhà cung cấp | Nhà cung cấp |
| AD01* | `GET /api/users` | Tra cứu tài khoản phục vụ quản lý | Quản trị |
| AD02* | `POST /api/users/{userId}/moderation` | Xử lý trạng thái tài khoản theo chính sách | Quản trị |
| AD03 | `GET /api/suppliers` | Lọc hồ sơ theo `verificationStatus` (mặc định `PENDING`), có phân trang | Quản trị |
| AD04 | `POST /api/suppliers/{supplierId}/verification` | Ra quyết định xác minh với `decision` và `reason` bắt buộc | Quản trị |

Lịch sử xác minh: `GET /api/suppliers/{supplierId}/verification-history` (quản trị). Ba endpoint này thuộc `AdminSupplierVerificationController` trong Identity, được nhóm thành **Admin - Supplier Verification** trên Swagger. Đường dẫn vẫn là `/api/suppliers`, không có route `/api/admin/suppliers`. `decision` hiện là enum `VERIFIED`, `REJECTED` hoặc `SUSPENDED` tùy trạng thái hiện tại; không phải trường cho admin đặt trạng thái tùy ý. Transition đang được code cho phép: `PENDING → VERIFIED/REJECTED`, `VERIFIED → SUSPENDED`, `SUSPENDED → VERIFIED`; hồ sơ `REJECTED` chỉ quay lại `PENDING` khi supplier sửa/gửi lại hồ sơ. Mọi quyết định của admin được lưu vào lịch sử xác minh.

Nhà cung cấp không tự thay đổi kết quả xác minh hoặc quyền quản trị.

## 4. Catalog & Store Service

### 4.1. Cửa hàng

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| ST01 | `GET /api/stores` | Tìm cửa hàng; lọc theo `supplierId` hoặc `mine` | Công khai; nhà cung cấp khi `mine=true` |
| ST02 | `GET /api/stores/{storeIdentifier}` | Xem cửa hàng theo ID hoặc slug | Công khai hoặc chủ cửa hàng |
| ST03 | `POST /api/stores` | Tạo cửa hàng | Nhà cung cấp |
| ST04 | `PATCH /api/stores/{storeId}` | Cập nhật thông tin, logo, banner, giao diện | Nhà cung cấp |
| ST05* | `DELETE /api/stores/{storeId}` | Xóa hoặc ngừng sử dụng theo chính sách lưu trữ | Nhà cung cấp |
| AD05* | `POST /api/stores/{storeId}/moderation` | Kiểm duyệt cửa hàng | Quản trị |

### 4.2. Danh mục, sản phẩm và biến thể

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| CA01 | `GET /api/categories` | Xem danh mục; lọc theo danh mục cha | Công khai |
| CA02 | `GET /api/categories/{categoryId}` | Xem chi tiết danh mục | Công khai |
| CA03* | `POST /api/categories` | Tạo danh mục | Quản trị |
| CA04* | `PATCH /api/categories/{categoryId}` | Cập nhật danh mục | Quản trị |
| CA05* | `DELETE /api/categories/{categoryId}` | Xóa danh mục khi đủ điều kiện | Quản trị |
| CA06 | `GET /api/products` | Tìm sản phẩm theo `q`, `storeId`, `categoryId`, `mine` | Công khai hoặc chủ cửa hàng |
| CA07 | `GET /api/products/{productId}` | Xem chi tiết sản phẩm | Công khai hoặc chủ cửa hàng |
| CA08 | `POST /api/products` | Tạo sản phẩm trong cửa hàng | Nhà cung cấp |
| CA09 | `PATCH /api/products/{productId}` | Cập nhật sản phẩm | Nhà cung cấp |
| CA10* | `DELETE /api/products/{productId}` | Xóa hoặc ngừng bán theo chính sách | Nhà cung cấp |
| CA11 | `GET /api/products/{productId}/variants` | Xem các biến thể | Công khai hoặc chủ cửa hàng |
| CA12 | `GET /api/products/{productId}/variants/{variantId}` | Xem chi tiết biến thể | Công khai hoặc chủ cửa hàng |
| CA13 | `POST /api/products/{productId}/variants` | Thêm biến thể | Nhà cung cấp |
| CA14 | `PATCH /api/products/{productId}/variants/{variantId}` | Cập nhật biến thể, giá và tồn kho theo quy tắc | Nhà cung cấp |
| CA15* | `DELETE /api/products/{productId}/variants/{variantId}` | Xóa hoặc ngừng bán biến thể theo chính sách | Nhà cung cấp |
| AD06* | `POST /api/products/{productId}/moderation` | Kiểm duyệt sản phẩm | Quản trị |

Chức năng tìm kiếm dùng `GET /api/products` với tham số truy vấn. API phải kiểm tra biến thể thuộc đúng sản phẩm và cửa hàng.

### 4.3. Khuyến mãi

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| CA16 | `GET /api/promotions` | Xem khuyến mãi; lọc theo cửa hàng | Công khai hoặc chủ cửa hàng |
| CA17 | `GET /api/promotions/{promotionId}` | Xem nội dung và điều kiện khuyến mãi | Công khai hoặc chủ cửa hàng |
| CA18 | `POST /api/promotions` | Tạo khuyến mãi và các điều kiện áp dụng | Nhà cung cấp |
| CA19 | `PATCH /api/promotions/{promotionId}` | Cập nhật thông tin và thời gian áp dụng | Nhà cung cấp |
| CA20 | `PUT /api/promotions/{promotionId}/rules` | Thay thế toàn bộ điều kiện khuyến mãi | Nhà cung cấp |
| CA21* | `DELETE /api/promotions/{promotionId}` | Xóa khuyến mãi theo chính sách | Nhà cung cấp |

Hỗ trợ `PERCENT_DISCOUNT`, `FIXED_DISCOUNT`, `BUY_X_GET_Y`. Sản phẩm điều kiện và sản phẩm tặng có thể khác nhau. Khuyến mãi bán lẻ không làm thay đổi giá báo giá đã chấp nhận.

## 5. Commerce Service

### 5.1. Giỏ hàng và đơn hàng

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| OR01 | `GET /api/cart` | Xem giỏ hàng hiện tại | Khách hàng |
| OR02 | `POST /api/cart/items` | Thêm biến thể vào giỏ | Khách hàng |
| OR03 | `PATCH /api/cart/items/{cartItemId}` | Thay đổi số lượng | Khách hàng |
| OR04 | `DELETE /api/cart/items/{cartItemId}` | Xóa một mục trong giỏ | Khách hàng |
| OR05 | `DELETE /api/cart/items` | Xóa toàn bộ mục trong giỏ | Khách hàng |
| OR06 | `POST /api/cart/pricing` | Tính thử giá, khuyến mãi và tổng tiền | Khách hàng |
| OR07 | `POST /api/checkout` | Đặt hàng, tách đơn theo cửa hàng | Khách hàng |

OR06 không nhận giá hoặc danh sách item từ client: server đọc toàn bộ cart active của principal, gọi IN07/IN08 theo store và trả `subtotal`, `discountTotal`, `totalAmount`, `stores[]` với line snapshot. Preview không giữ tồn kho; OR07 phải tính lại giá và reserve trước khi tạo đơn.
| OR08 | `GET /api/orders` | Xem lịch sử đơn hàng | Khách hàng |
| OR09 | `GET /api/orders/{orderId}` | Xem chi tiết đơn hàng tổng | Khách hàng |
| OR10 | `GET /api/seller-orders` | Xem danh sách đơn của nhà cung cấp | Nhà cung cấp |
| OR11 | `GET /api/seller-orders/{sellerOrderId}` | Xem phần đơn theo cửa hàng | Khách hàng hoặc nhà cung cấp liên quan |
| OR12* | `POST /api/seller-orders/{sellerOrderId}/prepare` | Bắt đầu chuẩn bị hàng khi đủ điều kiện | Nhà cung cấp |

`orderId` là đơn hàng tổng; `sellerOrderId` là phần đơn của một cửa hàng. Giá, thông tin sản phẩm và địa chỉ giao hàng được lưu tại thời điểm đặt hàng. Nhà cung cấp chỉ xem phần đơn thuộc cửa hàng của mình.

### 5.2. Thanh toán

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| FI01 | `GET /api/payments` | Xem lịch sử thanh toán theo phần đơn | Bên tham gia hoặc quản trị |
| FI02 | `GET /api/payments/{paymentId}` | Xem chi tiết thanh toán | Bên tham gia hoặc quản trị |
| FI03* | `POST /api/payments/{paymentId}/initiate` | Khởi tạo thanh toán với đối tác | Khách hàng |
| FI04* | `POST /api/payments/{provider}/callback` | Nhận kết quả thanh toán từ đối tác | Đối tác |

Server tạo nghĩa vụ `FULL`, `DEPOSIT`, `REMAINING` theo đơn hàng. Khách hàng không tự đặt số tiền thanh toán. Callback phải xác minh chữ ký, liên kết giao dịch và chống xử lý lặp.

### 5.3. Hủy đơn, hoàn tiền và tranh chấp

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| FI05* | `POST /api/cancellations/preview` | Kiểm tra điều kiện hủy và số tiền dự kiến hoàn | Bên tham gia hoặc quản trị |
| FI06* | `POST /api/cancellations` | Gửi yêu cầu hủy phần đơn | Bên tham gia hoặc quản trị |
| FI07 | `GET /api/cancellations` | Xem danh sách yêu cầu hủy | Bên tham gia hoặc quản trị |
| FI08 | `GET /api/cancellations/{cancellationId}` | Xem kết quả xử lý hủy | Bên tham gia hoặc quản trị |
| FI09 | `GET /api/refunds` | Xem danh sách hoàn tiền | Bên tham gia hoặc quản trị |
| FI10 | `GET /api/refunds/{refundId}` | Xem chi tiết hoàn tiền | Bên tham gia hoặc quản trị |
| FI11* | `POST /api/refunds` | Tạo hoàn tiền theo quyết định được phê duyệt | Quản trị |
| FI12 | `POST /api/disputes` | Mở tranh chấp đối với phần đơn | Bên tham gia theo chính sách |
| FI13 | `GET /api/disputes` | Xem danh sách tranh chấp | Bên tham gia hoặc quản trị |
| FI14 | `GET /api/disputes/{disputeId}` | Xem nội dung và tiến độ xử lý | Bên tham gia hoặc quản trị |
| FI15* | `POST /api/disputes/{disputeId}/resolve` | Giải quyết tranh chấp | Quản trị |

Số tiền hoàn phụ thuộc bên hủy, thời điểm và trạng thái đơn. Tổng tiền hoàn không vượt số tiền đủ điều kiện hoàn. Tranh chấp đang chặn xử lý phải ngăn việc chi trả cho nhà cung cấp.

### 5.4. Đối soát và chi trả

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| FI16 | `GET /api/seller-orders/{sellerOrderId}/settlement-eligibility` | Kiểm tra điều kiện đối soát | Nhà cung cấp hoặc quản trị |
| FI17 | `GET /api/settlements` | Xem danh sách đối soát; lọc theo `sellerOrderId` | Nhà cung cấp hoặc quản trị |
| FI18 | `GET /api/settlements/{settlementId}` | Xem chi tiết đối soát | Nhà cung cấp hoặc quản trị |
| FI19* | `POST /api/settlements` | Lập đối soát cho phần đơn | Quản trị |
| FI20* | `POST /api/settlements/{settlementId}/payout` | Yêu cầu chi trả cho nhà cung cấp | Quản trị |

Phí nền tảng được lưu tại thời điểm đối soát với mức 5% theo phạm vi hiện tại. Chỉ chi trả khi đủ điều kiện giao hàng/nghiệm thu và không có tranh chấp chặn. Nếu xử lý tự động, tác vụ hệ thống gọi service nội bộ thay cho API quản trị tương ứng.

### 5.5. Giao hàng, nghiệm thu và đánh giá

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| FU01 | `GET /api/shipments` | Xem danh sách vận chuyển theo phần đơn | Bên tham gia hoặc quản trị |
| FU02 | `GET /api/shipments/{shipmentId}` | Xem lịch trình và thông tin theo dõi | Bên tham gia hoặc quản trị |
| FU03 | `POST /api/shipments` | Lập kế hoạch giao hàng | Nhà cung cấp |
| FU04* | `PATCH /api/shipments/{shipmentId}` | Cập nhật kế hoạch và thông tin vận chuyển | Nhà cung cấp |
| FU05* | `POST /api/shipments/{shipmentId}/ship` | Xác nhận đã gửi hàng | Nhà cung cấp hoặc bên được cấp quyền |
| FU06* | `POST /api/shipments/{shipmentId}/delivered` | Xác nhận đã giao hàng | Bên được cấp quyền xác nhận giao hàng |
| FU07* | `POST /api/shipments/providers/{provider}/callback` | Nhận trạng thái từ đơn vị vận chuyển | Đối tác |
| FU08 | `POST /api/seller-orders/{sellerOrderId}/accept` | Khách hàng nghiệm thu phần đơn | Khách hàng |
| FU09 | `POST /api/reviews` | Đánh giá mục hàng đã mua | Khách hàng |
| FU10 | `GET /api/reviews` | Xem đánh giá; lọc theo biến thể, cửa hàng hoặc `mine` | Công khai; khách hàng khi `mine=true` |
| FU11 | `GET /api/reviews/{reviewId}` | Xem nội dung đánh giá được phép hiển thị | Công khai hoặc tác giả |
| AD08* | `POST /api/reviews/{reviewId}/moderation` | Kiểm duyệt đánh giá | Quản trị |

Thời điểm dự kiến và thực tế được lưu riêng. Một phần đơn có thể có nhiều lần vận chuyển. Mỗi mục hàng có tối đa một đánh giá, điểm từ 1 đến 5.

## 6. RFQ & Negotiation Service

**Cập nhật backend TV4 (14/10/2026):** RFQ create/list/detail/PATCH/close/cancel, quotation create/list/detail/revision/accept/reject/cancel/order lookup và conversation/chat đã có implementation. Contract và các quyết định áp dụng nằm trong [TV4_BACKEND_GUIDE.md](TV4_BACKEND_GUIDE.md). Create là publish/submit ngay; không triển khai endpoint draft/publish/submit thừa. Accept nhận `{quotationVersion,addressId}`, yêu cầu `Idempotency-Key`, trả 200 COMPLETED hoặc 202 PENDING có tra cứu/recovery bền vững. Frontend tiếp tục triển khai sau backend.

### 6.1. Yêu cầu báo giá

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| RQ01 | `GET /api/rfqs` | Tìm yêu cầu báo giá công khai và yêu cầu gửi riêng cho cửa hàng của người gọi; hỗ trợ `mine` | Công khai; khách hàng khi `mine=true` |
| RQ02 | `GET /api/rfqs/{rfqId}` | Xem nội dung yêu cầu báo giá | Công khai hoặc chủ yêu cầu; yêu cầu gửi riêng chỉ chủ yêu cầu và nhà cung cấp được mời |
| RQ03 | `POST /api/rfqs` | Tạo yêu cầu báo giá; `targetStoreIds` (1–20) để chỉ gửi cho cửa hàng chọn | Khách hàng |
| RQ04* | `PATCH /api/rfqs/{rfqId}` | Cập nhật nội dung khi được phép | Khách hàng |
| RQ05* | `POST /api/rfqs/{rfqId}/publish` | Công bố yêu cầu đang ở bản nháp | Khách hàng |
| RQ06* | `POST /api/rfqs/{rfqId}/close` | Đóng nhận báo giá | Khách hàng |
| RQ07* | `POST /api/rfqs/{rfqId}/cancel` | Hủy yêu cầu theo điều kiện nghiệp vụ | Khách hàng |
| AD07* | `POST /api/rfqs/{rfqId}/moderation` | Kiểm duyệt yêu cầu báo giá | Quản trị |

RQ05 áp dụng khi nhóm lựa chọn quy trình bản nháp trước công bố. Hủy RFQ không tự động hủy các đơn hàng đã hình thành.

### 6.2. Báo giá và chấp nhận báo giá

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| RQ08 | `GET /api/rfqs/{rfqId}/quotations` | Xem các báo giá để so sánh | Chủ RFQ; nhà cung cấp xem báo giá của mình |
| RQ09 | `POST /api/rfqs/{rfqId}/quotations` | Tạo báo giá cho yêu cầu | Nhà cung cấp |
| RQ10 | `GET /api/quotations` | Xem danh sách báo giá của nhà cung cấp | Nhà cung cấp |
| RQ11 | `GET /api/quotations/{quotationId}` | Xem chi tiết một phiên bản báo giá | Chủ RFQ hoặc nhà cung cấp báo giá |
| RQ12* | `PATCH /api/quotations/{quotationId}` | Cập nhật báo giá nháp | Nhà cung cấp |
| RQ13* | `POST /api/quotations/{quotationId}/submit` | Gửi báo giá từ bản nháp | Nhà cung cấp |
| RQ14 | `POST /api/quotations/{quotationId}/revisions` | Tạo phiên bản báo giá mới | Nhà cung cấp |
| RQ15 | `POST /api/quotations/{quotationId}/accept` | Chấp nhận báo giá và yêu cầu tạo đơn | Chủ RFQ |
| RQ16* | `POST /api/quotations/{quotationId}/reject` | Từ chối báo giá | Chủ RFQ |
| RQ17* | `POST /api/quotations/{quotationId}/cancel` | Rút báo giá trước khi chấp nhận theo chính sách | Nhà cung cấp |
| RQ18 | `GET /api/quotations/{quotationId}/order` | Tra cứu kết quả chuyển báo giá thành đơn | Chủ RFQ hoặc nhà cung cấp báo giá |

Một RFQ có thể có nhiều báo giá được chấp nhận. Báo giá đã chấp nhận không được sửa nội dung giao dịch. Mỗi báo giá chỉ được chuyển thành tối đa một phần đơn; gửi lại yêu cầu không tạo đơn trùng.

Contract TV4 hiện tại: create/revision có 1–50 mục hàng, yêu cầu `Idempotency-Key` và trả `version` do server cấp. Revision thêm `expectedVersion`, không nhận `storeId`, chỉ áp dụng bản mới nhất `SUBMITTED` chưa có conversion khi RFQ còn mở/còn hạn. Accept nhận `quotationVersion` như mục 8; reject/cancel dùng reason tùy chọn và gửi lại cùng quyết định/reason là an toàn. Chi tiết bắt buộc/nullable xem [TV4_BACKEND_GUIDE.md](TV4_BACKEND_GUIDE.md).

### 6.3. Hội thoại và tin nhắn

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| CH01 | `POST /api/rfqs/{rfqId}/conversations` | Tạo hoặc lấy hội thoại với nhà cung cấp | Chủ RFQ hoặc nhà cung cấp tham gia |
| CH02 | `GET /api/conversations` | Xem danh sách hội thoại của tài khoản | Bên tham gia |
| CH03 | `GET /api/conversations/{conversationId}` | Xem thông tin hội thoại | Bên tham gia |
| CH04 | `GET /api/conversations/{conversationId}/messages` | Xem lịch sử tin nhắn | Bên tham gia |
| CH05 | `GET /api/conversations/unread-count` | Đếm tin bên kia gửi sau lần đọc cuối, trên mọi hội thoại | Người dùng đã đăng nhập |
| CH06 | `POST /api/conversations/{conversationId}/read` | Đánh dấu đã đọc tới tin mới nhất (204) | Bên tham gia |

Lịch sử tin nhắn sử dụng `beforeMessageId` hoặc `afterMessageId` và `limit` (mặc định 50, tối đa 100). Phản hồi gồm `items`, `nextCursor`, `hasMore`.

| Kênh WebSocket/STOMP | Chức năng | Dữ liệu chính |
|---|---|---|
| `WS /ws/chat` | Mở kết nối qua Gateway | Thông tin xác thực kết nối |
| `SEND /app/conversations/{conversationId}/messages` | Gửi tin nhắn | `clientMessageId`, `messageType`, `content` |
| `SUBSCRIBE /topic/conversations/{conversationId}` | Nhận tin nhắn | `messageId`, `conversationId`, `senderUserId`, `content`, `sentAt` |

Server kiểm tra quyền khi gửi và đăng ký nhận tin. Tin nhắn được lưu trước khi phát đến người nhận. Cơ chế lưu định danh gửi lặp cần được bổ sung để bảo đảm không tạo tin nhắn trùng khi kết nối lại.

## 7. API nội bộ giữa các dịch vụ

Các endpoint dưới đây dành cho service-to-service, không được Gateway công khai và frontend không gọi trực tiếp. Identity/Catalog đang xác thực internal request bằng `X-Service-Name` và `X-Service-Token` theo service gọi, không dùng JWT người dùng thay thế service token; quyền mỗi caller được giới hạn theo route. Các controller `InternalAddressController`, `InternalSupplierController`, `InternalUserController` được ẩn khỏi Swagger UI của Identity bằng `@Hidden`, **không phải bị xóa hoặc đổi đường dẫn**. Việc ẩn tài liệu không phải biện pháp bảo mật thay cho xác thực. Những contract nội bộ chưa triển khai vẫn được liệt kê để phối hợp giữa các thành viên.

| Mã | Phương thức và endpoint | Cung cấp → sử dụng | Dữ liệu trao đổi |
|---|---|---|---|
| IN01 | `GET /internal/v1/users/{userId}/summary` | Identity → Commerce, RFQ | Định danh người dùng, khách hàng và thông tin cần thiết |
| IN02 | `GET /internal/v1/addresses/{addressId}/summary` | Identity → Commerce | Địa chỉ giao hàng và customerId để kiểm tra chủ sở hữu |
| IN03 | `GET /internal/v1/suppliers/{supplierId}/summary` | Identity → Catalog, Commerce, RFQ | Định danh và thông tin nhà cung cấp |
| IN04 | `GET /internal/v1/suppliers/by-user/{userId}/summary` | Identity → Catalog | Hồ sơ nhà cung cấp của người dùng |
| IN05 | `GET /internal/v1/stores/{storeId}/summary` | Catalog → Commerce, RFQ | Cửa hàng, nhà cung cấp và tên hiển thị |
| IN06 | `GET /internal/v1/products/{productId}/variants/{variantId}/snapshot` | Catalog → Commerce, RFQ | Thông tin sản phẩm và biến thể tại thời điểm truy vấn |
| IN07 | `POST /internal/v1/variants/snapshots` | Catalog → Commerce | Danh sách thông tin biến thể theo variantIds |
| IN08 | `POST /internal/v1/promotions/calculate` | Catalog → Commerce | Kết quả tính khuyến mãi theo cửa hàng và các mục hàng |
| IN09 | `POST /internal/v1/orders/from-accepted-quotation` | Commerce ← RFQ | Tạo đơn từ báo giá được chấp nhận |
| IN10 | `GET /internal/v1/orders/by-quotation/{quotationId}` | Commerce → RFQ | Tra cứu đơn đã tạo từ báo giá |
| IN12 | `POST /internal/v1/inventory/reservations`, `GET /{reservationKey}`, `PUT /{reservationKey}/confirm`, `PUT /{reservationKey}/release` | Catalog → Commerce | Giữ, tra cứu, xác nhận hoặc giải phóng tồn kho bằng cùng reservation key; chỉ caller `commerce` |

IN09 đã có DTO cho định danh RFQ/báo giá/phiên bản, khách hàng, nhà cung cấp, cửa hàng, thời điểm chấp nhận, snapshot cửa hàng/địa chỉ, các mục hàng, giá và điều khoản. Kết quả định nghĩa `orderId`, `sellerOrderId`, `quotationId`, `quotationVersion`, `status`. Controller hiện trả 501 với payload hợp lệ vì chưa có conversion/idempotency/obligation; không coi DTO này là chức năng đã hoàn thành. IN10 chưa có route.

Endpoint IN11 `GET /internal/v1/seller-orders/{sellerOrderId}/payment-context` hiện có trong mã nguồn. Chức năng này phục vụ trao đổi trong Commerce và được thiết kế sử dụng lời gọi service nội bộ; không bắt buộc gọi HTTP giữa TV3, TV5 và TV6.

IN12 hiện có contract và migration Catalog, nhưng chưa được checkout/payment gọi. `expiresAt` do Commerce cung cấp với TTL giữ kho 15 phút; Catalog trả `EXPIRED` khi hết hạn và không cho confirm. Dùng cùng key/payload khi retry; xem chi tiết tại `docs/contracts/README.md`.

## 8. Dữ liệu request và response chính

Các field liệt kê là dữ liệu nghiệp vụ chính, không thay thế khai báo đầy đủ về bắt buộc/cho phép rỗng trong OpenAPI. Server quản lý định danh người thực hiện, trạng thái, thời điểm và tổng tiền theo từng nghiệp vụ.

| Nhóm API | Dữ liệu đầu vào chính | Kết quả chính |
|---|---|---|
| Đăng ký | `fullName`, `email`, `password`, `phone` | Thông tin tài khoản; không trả mật khẩu hoặc mã băm |
| Đăng nhập | `email`, `password` | `accessToken`, `tokenType`, `expiresIn`, `user` |
| Hồ sơ cá nhân | `fullName`, `phone` | `userId`, `customerId`, hồ sơ và trạng thái |
| Địa chỉ | `recipientName`, `recipientPhone`, `addressLine`, `ward`, `district`, `city`, `isDefault` | Địa chỉ và `addressId` |
| Hồ sơ nhà cung cấp | `companyName`, `taxCode` | `supplierId`, thông tin và kết quả xác minh |
| Cửa hàng | `name`, `logoUrl`, `bannerUrl`, `themeKey` | `storeId`, `storeCode`, `slug`, thông tin cửa hàng |
| Danh mục | `name`, `slug`, `parentCategoryId` | `categoryId`, thông tin danh mục |
| Sản phẩm | `storeId`, `categoryId`, `name`, `description`; cách cấp `productCode` cần xác nhận | `productId`, mã và thông tin sản phẩm |
| Biến thể | `sku`, `variantName`, `imageUrl`, `basePrice`, `stockQuantity` | `variantId`, giá, tình trạng bán |
| Khuyến mãi | `storeId`, `name`, `startAt`, `endAt`, `rules` | Khuyến mãi và điều kiện áp dụng |
| Điều kiện khuyến mãi | `ruleType`, `targetVariantId`, `rewardVariantId`, `minQuantity`, `discountPercent`, `discountAmount`, `rewardQuantity` theo loại | Quy tắc giảm giá hoặc tặng hàng |
| Giỏ hàng | `variantId`, `quantity` | Các mục hàng và kết quả tính giá |
| Checkout | `addressId`, lựa chọn vận chuyển nếu có | Đơn tổng, các phần đơn và tổng tiền do server tính |
| RFQ | `title`, `quantity`, `description`, `budgetAmount`, `requestedDeliveryDate`, `deliveryLocation`, `customizationRequirements`, `expiresAt` | RFQ và trạng thái |
| Báo giá | `storeId`, `depositPercent`, `validUntil`, `leadTimeDays`, `proposedDeliveryDate`, `shippingMethod`, `shippingFee`, `paymentTerms`, `items` | Phiên bản báo giá, các mục hàng và tổng tiền |
| Mục báo giá | `productId` + `variantId` nếu dùng Catalog; cả hai rỗng nếu tùy chỉnh, `itemNameSnapshot`, `skuSnapshot`, `quantity`, `unitPrice`, `discountAmount`, `customizationNote` | Tên/SKU từ Catalog cho biến thể; tên tùy chỉnh từ request; giá thương lượng và subtotal do server kiểm tra/tính |
| Chấp nhận báo giá | `quotationVersion`, `addressId`; quotationId từ URL | Trạng thái chuyển đổi và định danh đơn khi hoàn tất |
| Thanh toán | `paymentId` từ URL; lựa chọn đối tác theo cấu hình | Trạng thái và thông tin thực hiện thanh toán |
| Hủy đơn | `sellerOrderId`, `reason` | Kết quả hủy và mức hoàn theo chính sách |
| Hoàn tiền | `paymentId`, `amount`, `reason`, `cancellationId` nếu có | `refundId`, số tiền và trạng thái |
| Tranh chấp | `sellerOrderId`, `reason`, `evidenceUrl`; xử lý gồm `outcome`, `resolution` | Nội dung, trạng thái và kết quả xử lý |
| Đối soát | `sellerOrderId`; payout dùng settlementId từ URL | `grossAmount`, `feeRate`, `feeAmount`, `supplierPayoutAmount`, trạng thái |
| Giao hàng | `sellerOrderId`, `shippingMethod`, `provider`, `trackingCode`, các mốc dự kiến | Thông tin vận chuyển, các mốc thực tế và trạng thái |
| Đánh giá | `orderItemId`, `rating`, `comment` | `reviewId`, điểm, nội dung và thời điểm đánh giá |

Quyền xem response được giới hạn theo vai trò. Nhà cung cấp không nhận dữ liệu của nhà cung cấp khác; API công khai không trả địa chỉ, thanh toán hoặc báo giá riêng.

## 9. Quy tắc nghiệp vụ cần kiểm tra

1. Checkout nhiều cửa hàng tạo một đơn tổng và các phần đơn tương ứng; lưu thông tin sản phẩm, giá và địa chỉ tại thời điểm đặt hàng.
2. Một RFQ có thể nhận và chấp nhận nhiều báo giá. Báo giá đã chấp nhận được giữ nguyên nội dung giao dịch.
3. Tiền đặt cọc chỉ phát sinh sau khi chấp nhận báo giá. Phần còn lại đến hạn trước ngày giao đã thống nhất 3 ngày.
4. Thanh toán thành công được giữ lại đến khi đủ điều kiện đối soát. Phí nền tảng được lưu với mức 5% trong phạm vi hiện tại.
5. Hủy đơn không mặc định hoàn 100%. Tranh chấp chặn phải được giải quyết trước chi trả.
6. Request lặp, callback lặp hoặc hết thời gian chờ không được tạo đơn, hoàn tiền hoặc chi trả hai lần.
7. Lỗi giữa RFQ và Commerce được xử lý bằng định danh giao dịch, trạng thái lưu bền vững và tra cứu kết quả; transaction tại một dịch vụ không tự hoàn tác dữ liệu của dịch vụ khác.

## 10. Các nội dung cần xác nhận

| Nội dung | Phạm vi cần thống nhất |
|---|---|
| Quản trị và xác minh | Người được cấp quyền, trạng thái xử lý và lưu lịch sử thao tác |
| Xóa dữ liệu | Điều kiện xóa, ngừng sử dụng và thời gian lưu trữ |
| Khuyến mãi và tồn kho | Thứ tự áp dụng, cách tính giảm giá, làm tròn và xử lý đặt hàng đồng thời |
| Báo giá | Quy trình bản nháp, phân bổ số lượng, nhóm đơn khi chấp nhận nhiều báo giá |
| Thanh toán | Đối tác, chữ ký callback, cách lưu trạng thái giữ/giải ngân, xử lý khoản tiền bằng 0 |
| Hủy, hoàn, tranh chấp | Chính sách theo thời điểm, thẩm quyền giải quyết và mức khấu trừ |
| Giao hàng và đánh giá | Quyền xác nhận giao hàng, hoàn tất nhiều chuyến, thời hạn nghiệm thu và điều kiện đánh giá |

### 10.1. API tài khoản mở rộng

Các API sau được đề xuất bổ sung; chưa mặc định thuộc phạm vi MVP đã phê duyệt.

| Mã | Phương thức và endpoint | Chức năng | Quyền |
|---|---|---|---|
| AU01* | `POST /api/auth/refresh` | Cấp access token mới bằng thông tin làm mới hợp lệ | Chủ phiên đăng nhập |
| AU02* | `POST /api/auth/logout` | Kết thúc phiên theo cơ chế thu hồi được lựa chọn | Chủ phiên đăng nhập |
| AU03* | `POST /api/auth/password-change` | Đổi mật khẩu sau khi xác thực lại | Người dùng |
| AU04* | `POST /api/auth/password-reset-requests` | Yêu cầu khôi phục mật khẩu | Công khai |
| AU05* | `POST /api/auth/password-resets` | Đặt mật khẩu mới bằng mã khôi phục hợp lệ | Chủ mã khôi phục |

## 11. Cơ sở tài liệu

- Đặc tả dự án: `MSS_Mooncake_Marketplace_Project_Spec_v3_Microservices.docx`.
- Kế hoạch phân chia dịch vụ: `MSS_4Service_6Member_Workplan_and_Scaffold_v3.md`.
- Mô hình dữ liệu: `MSS_Physical_Data_Model_SQLServer_v1.md` và migration của từng dịch vụ.
- Quy ước triển khai chi tiết: [CODING_CONVENTION_AND_API_RULES.md](CODING_CONVENTION_AND_API_RULES.md).
