# TV1–TV6 Flow Repair Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sửa các điểm đứt quyền, trạng thái và dữ liệu giữa checkout, payment, fulfillment, settlement; loại dữ liệu giả khỏi luồng FE mặc định.

**Architecture:** Giữ 4 business services và 1 Gateway, mô hình 3 lớp với service interface/implementation hiện tại. TV3/TV5/TV6 gọi Java service nội bộ trong Commerce, giữ transaction local; Catalog/Identity/RFQ giao tiếp OpenFeign/REST. Không thêm broker hay service mới.

**Tech Stack:** Java, Spring Boot, Maven, SQL Server/Flyway, OpenFeign, React/TypeScript/Vite, PayOS SDK hiện có.

**Spec:** `TODO.md`, `TODO_IMPLEMENTATION.md`, `docs/CODING_CONVENTION_AND_API_RULES.md`, `docs/API_SPECIFICATION.md`, `docs/contracts/README.md`, `docs/TV5_FINANCE_INTEGRATION.md`, cùng các quyết định nghiệp vụ đã chốt trong cuộc trao đổi. Các TODO cũ phải đối chiếu code và quyết định mới, không coi chúng là bằng chứng chưa implement.

## Global Constraints

- Không sửa migration Flyway đã chạy; bổ sung migration với version chưa dùng khi triển khai.
- Không reset DB, sửa payment status thủ công, gọi chuyển tiền thật hoặc commit/push trong phạm vi plan này.
- Giữ snapshot giá/sản phẩm/địa chỉ của đơn, idempotency checkout 30 phút, reservation 15 phút và phí cố định/store đã chốt.
- Tiền nguyên VND; không thay thứ tự/rounding khuyến mãi trong đợt sửa này.
- Không suy ra thanh toán thành công từ redirect, thời gian hết hạn local hoặc FE.
- Bản ghi SUCCESS và tiến độ giao hàng đã đi tới không bị request lặp/response cũ đưa lùi trạng thái.
- Test SQL dùng database test riêng; cần kiểm tra cấu hình trước khi chạy vì có test tích hợp ghi dữ liệu.
- Giữ các thay đổi sẵn có của người dùng (.gitignore, run configurations, RFQ application.yml).

## Review Focus

1. customerId, supplierId, userId khác nhau; tài khoản có đồng thời role customer và supplier vẫn xem được shipment thuộc store mình.
2. Callback lặp hoặc hai callback đồng thời chỉ thu một lần, không làm lùi đơn đang giao.
3. Khách trả tiền trước hạn nhưng webhook đến sau yêu cầu hủy: không hoàn kho dựa vào expires_at local.
4. PayOS không phản hồi hoặc kết quả chưa xác minh: giữ pending/reconciliation, không tạo link hoặc hoàn kho mù.
5. Backend catalog lỗi, sản phẩm bị ẩn hoặc không có promotion/review: FE hiển thị trạng thái thật, không bù bằng dữ liệu mock.

## Điểm cần chốt trước phần giao hàng

Tài liệu hiện có quy định REMAINING đến hạn trước ngày giao 3 ngày, nhưng chưa xác định rõ thời điểm cho phép PREPARING/SHIPPING khi mới trả DEPOSIT. Không tự chọn một trong hai nghiệp vụ.

**Đề xuất để duyệt:** DEPOSIT đủ cho phép chuẩn bị hàng; SHIPPED yêu cầu thu đủ tổng tiền. FULL thu đủ cho phép chuẩn bị/giao hàng. Đây là đề xuất, chưa phải business rule được chấp thuận. Task 1 và 3 chỉ hoàn tất policy này sau khi nhóm xác nhận; các task khác thực hiện độc lập.

## Task 1 — Nối thanh toán và trạng thái đơn (TV3/TV5)

**Files:** `CommerceService/src/main/java/mss/mooncake/commerce/service/impl/FinanceServiceImpl.java`, `CustomerPaymentServiceImpl.java`, `FinanceIntegrationServiceImpl.java`; `service/FinanceIntegrationService.java`; `repository/FinanceRepository.java`, `repository/impl/FinanceRepositoryImpl.java`.

**Tests:** mở rộng `CommerceService/src/test/java/mss/mooncake/commerce/FinanceServiceTest.java`; thêm `service/PaymentOrderLifecycleIntegrationTest.java` dùng SQL test riêng.

**Interfaces:** tái sử dụng `FinanceIntegrationService`, thêm `void syncCollectedPayment(long sellerOrderId)`; tính từ payment SUCCESS và context đã lưu, không nhận số tiền/status client tự khai báo. Cả hai collect() gọi cùng đường cập nhật local trong transaction đang thu tiền.

- [ ] Test trước: FULL/customer payment thành công cập nhật đúng từng seller order; DEPOSIT/REMAINING theo policy được duyệt; callback lặp không ghi ledger hai lần.
- [ ] Test callback đến khi đơn SHIPPING/DELIVERED/ACCEPTED không đưa lùi về READY; payment đến sau CANCELLED phải được ghi nhận là tiền nhận muộn cần xử lý, không tự hồi sinh đơn.
- [ ] Thêm cập nhật trạng thái có điều kiện dưới khóa seller order; đồng bộ status customer order theo toàn bộ seller orders. Viết bảng trạng thái tổng hợp dựa trên enum/contract hiện có, ghi rõ trường hợp nhiều seller khác trạng thái; không tạo nhãn status mới tùy ý.
- [ ] Gọi chung từ collect đơn lẻ và collect tổng đơn; ghi cùng transaction với inbox/payment/ledger, lỗi thì rollback toàn bộ.
- [ ] Chạy test task và cập nhật contract payment/order. Chỉ đánh dấu pass khi đã xác nhận trạng thái DB, không chỉ response mock.

## Task 2 — Quyền vận chuyển và ID nghiệp vụ (TV1/TV6)

**Files:** `CommerceService/.../controller/ShipmentController.java`, `OrderAcceptanceController.java`, `ReviewController.java`; `security/SecurityConfig.java`; `service/FulfillmentApplicationService.java`, `service/impl/FulfillmentApplicationServiceImpl.java`; tái sử dụng `FinanceActorResolver`/Identity client.

**Tests:** thêm `CommerceService/src/test/java/mss/mooncake/commerce/controller/ShipmentAuthorizationTest.java`; cập nhật `service/FulfillmentApplicationServiceImplTest.java` và caller liên quan.

**Interfaces:** nhận actor đã resolve từ `FinanceActorResolver.resolve(Authentication)` thay vì long ID không rõ loại. Resolve supplier trước transaction DB; mutation supplier phải kiểm tra supplier còn VERIFIED qua contract Identity. Admin được quyền gì phải giữ đúng API contract, không tự mở rộng quyền.

- [ ] Test userId khác supplierId: chủ store thao tác được; người dùng khác có userId trùng supplierId vẫn bị 403.
- [ ] Test anonymous, customer-only, supplier không sở hữu, supplier bị suspend, admin theo phạm vi hiện có; Identity unavailable phải fail closed.
- [ ] Test đọc shipment cho buyer hoặc supplier owner kể cả actor có cả hai role; không chọn nhánh customer chỉ vì có ROLE_CUSTOMER.
- [ ] Bỏ fallback customerId về userId trong accept/review; ID thiếu phải trả lỗi xác thực/quyền rõ ràng.
- [ ] Cập nhật interface, implementation, controller và tests cùng lượt; giữ URL/DTO public nếu không cần đổi.

## Task 3 — State transition và đồng bộ nghiệm thu (TV6/TV5)

**Files:** `CommerceService/.../service/impl/FulfillmentApplicationServiceImpl.java`, `FinanceIntegrationServiceImpl.java`; `repository/SellerOrderRepository.java`, `ShipmentRepository.java`; entity/migration nếu thực sự cần version mới.

**Interfaces:** dùng `syncFulfillment(long sellerOrderId, long version, boolean delivered, boolean acceptedByBuyer)` hiện có. Tái sử dụng khóa/version bền vững; không dùng timestamp hoặc bộ đếm RAM làm version. Tất cả writer payment/ship/accept/cancel phải tuân theo cùng thứ tự khóa đã kiểm tra để tránh deadlock.

- [ ] Test PLANNED không được giao thẳng DELIVERED; SHIPPED mới được DELIVERED; request lặp thành công trả cùng kết quả, không đổi thời gian lần đầu.
- [ ] Test đơn CANCELLED, đang tranh chấp hoặc chưa đạt policy thanh toán không thể ship; tạo shipment trên đơn đã kết thúc bị chặn theo bảng chuyển trạng thái.
- [ ] Dùng lock/version chống ship/cancel đồng thời và hai lần tạo/cập nhật làm ghi đè tiến độ. Không tự giới hạn một shipment/order nếu tài liệu chưa quy định.
- [ ] Khi ghi delivered/accepted gọi syncFulfillment trong cùng transaction, lưu version tăng đơn điệu, không thay đổi acceptedAt khi retry.
- [ ] Test delivered + buyer accepted + đã thu đủ + fee config hợp lệ + không dispute thì settlement eligible; thiếu từng điều kiện phải bị chặn. Mock kiểm tra lời gọi chưa đủ: thêm test SQL đọc finance_context sau commit.

## Task 4 — Hủy đơn, đối soát và hoàn kho an toàn (TV3/TV5/TV2)

**Files:** `CommerceService/.../service/impl/CustomerPaymentServiceImpl.java`, `FinanceServiceImpl.java`; Finance repository; Catalog `StockReservationServiceImpl.java` nếu test contract phát hiện lệch.

**Tests:** thêm `CommerceService/src/test/java/mss/mooncake/commerce/service/UnpaidCancellationIntegrationTest.java`; mở rộng finance/provider/reservation tests.

- [ ] Test quá expires_at local nhưng PayOS PAID: ghi nhận tiền và chặn hủy chưa trả; không hoàn kho.
- [ ] Test PayOS timeout/not-found/credentials lỗi không bị quy thành EXPIRED hoặc chưa trả. Phân loại lỗi qua SDK hiện có, giữ trạng thái cần đối soát; not-found cần xác minh đúng kênh và policy recovery, không mặc định cho retry tạo mới.
- [ ] Bỏ nhánh kết luận EXPIRED chỉ dựa đồng hồ; đối soát/cancel mọi link liên quan cả payment đơn lẻ và customer_payment trước khi hoàn kho.
- [ ] Tuần tự hóa intent hủy với thu tiền. Không giữ transaction DB trong lúc gọi PayOS/Catalog; lưu intent và kiểm tra lại sau mỗi bước.
- [ ] Test callback chen giữa xác nhận hủy và hoàn kho; không cho cancel/refund khác vượt qua intent đang xử lý. Mọi đường approve cancellation đều phải kiểm tra active customer_payment, không chỉ payment đơn lẻ.
- [ ] Test Catalog đã hoàn kho nhưng response mất: retry cùng reservation key không cộng hai lần; crash trước bước finalize được recovery bằng intent bền vững, không cần sửa SQL tay.
- [ ] Không hoàn tiền tự động; late payment phải ghi inbox/ledger idempotent và đưa về xử lý tài chính theo policy hiện có. Giữ chứng cứ thay vì bỏ webhook vì đơn đã hủy.

## Task 5 — Enum và hợp đồng trạng thái payment (TV5)

**Files:** `CommerceService/.../enums/PaymentStatus.java`, `entity/PaymentEntity.java`, finance DTO/controller advice; `MoonCakeMarketFrontend/src/utils/status.ts`, `features/payment-settlement/paymentApi.ts` và component thanh toán.

- [ ] Liệt kê toàn bộ status thực tế mà writer finance ghi và constraints SQL hiện tại; bổ sung enum tương ứng, không sửa dữ liệu lịch sử.
- [ ] Test đọc PaymentEntity bằng JPA với mỗi trạng thái writer có thể ghi, gồm CREATING/RECONCILING/CANCELLING/CANCELLED/EXPIRED.
- [ ] Giữ customer_payment và payment là hai vòng đời riêng; FE nhận trạng thái chưa biết phải hiển thị rõ, không mặc định là thành công.
- [ ] Test FE cung cấp đối soát khi thiếu URL, không mở QR rỗng, giữ idempotency khi chưa biết kết quả.

## Task 6 — Bỏ mock trong luồng mặc định (FE/TV1–TV6)

**Files:** FE `src/context/CatalogContext.tsx`, `App.tsx`, `pages/UserProfile.tsx`, `Home.tsx`, `ProductDetail.tsx`, `StoreDetail.tsx`, `components/home/StoreCard.tsx`, `components/AIAssistantModal.tsx`; tái sử dụng `services/api/catalogApi.ts`, `catalogMappers.ts` và API review hiện có.

- [ ] Catalog lỗi: hiển thị lỗi + retry; dữ liệu mock chỉ xuất hiện khi bật chế độ demo rõ ràng. API trả rỗng là empty state; không fallback mock từ selected product/store hoặc findStore trong chế độ thật.
- [ ] Hồ sơ lấy promotions thật qua Catalog, tên store từ API. Bỏ giá tham khảo hard-code; chỉ hiển thị min/max khi dữ liệu API đã có đủ nghĩa, nếu chưa có thì ẩn trong đợt này.
- [ ] Product review dùng variant ID thật qua API Commerce; rỗng hiện chưa có review. Không dùng rating/sales giả để làm người dùng hiểu là dữ liệu thống kê thật.
- [ ] Không hiển thị flash sale/bán chạy từ mock trong chế độ thật. Blog có thể giữ nội dung biên tập tĩnh; AI lỗi phải thông báo lỗi thay vì kết quả giả.
- [ ] Yêu thích giữ localStorage theo user trong phạm vi sửa lỗi, bỏ fallback mock khi tra sản phẩm thật; ghi rõ chưa đồng bộ thiết bị. Chưa thêm DB/API yêu thích vì chưa nằm trong scope/physical đã duyệt.
- [ ] Test API timeout, empty response, product bị ẩn, logout/login đổi account; đảm bảo cart/checkout chỉ dùng ID thật trong chế độ thật.

## Task 7 — Kiểm thử xuyên TV1–TV6 và bàn giao

**Files:** tests mỗi service, `docs/API_SPECIFICATION.md`, `docs/contracts/README.md`, `docs/TV5_FINANCE_INTEGRATION.md`, `TODO_IMPLEMENTATION.md`; thêm hướng dẫn smoke test trong docs. Chỉnh FE API client chỉ khi contract thực sự đổi.

- [ ] Xử lý môi trường test Java trước: audit gần nhất chạy bằng Java 24 dù source release 21, Gateway lỗi loopback cả ngoài sandbox. Kiểm tra bằng JDK dự án hỗ trợ; không bỏ/skip test để có BUILD SUCCESS.
- [ ] Chạy targeted tests mỗi task bằng Maven module; SQL integration tests chỉ bật trên DB test riêng theo cấu hình thực tế trong repo. Sau đó chạy `.\mvnw.cmd -B -ntp test` ở root backend.
- [ ] FE chạy `npm run lint` và `npm run build`; thêm test regression tập trung với hạ tầng sẵn có, browser smoke test cho hành vi giao diện.
- [ ] TV1: register/login → address ownership → supplier onboarding/admin verify → store ownership/public status.
- [ ] TV2/TV3: hai store → cart → giá/promotion → checkout → 1 customer order + 2 seller orders → snapshot và tồn kho đúng; retry cùng key không tạo thêm đơn.
- [ ] TV4/TV3/TV5: RFQ → quotation/revise → accept → order snapshot/payment obligations đúng → mất response/retry không tạo thêm đơn; chat hai account chặn người ngoài conversation.
- [ ] TV5/TV6: mock provider xác thực → FULL, DEPOSIT/REMAINING theo policy → prepare/ship/deliver → buyer accept → settlement; test dispute/refund/cancel và duplicate callback.
- [ ] PayOS thật chỉ xác minh khi môi trường được phép và cấu hình đúng; không chuyển tiền thật trong test tự động. Báo riêng bằng chứng unit/integration/mock provider và kiểm thử PayOS thật.
- [ ] Cập nhật TODO/docs khớp kết quả thực tế; ghi các rule chưa chốt, migration cần chạy và giới hạn còn lại. Không công bố toàn bộ TV đạt nếu có test bắt buộc chưa chạy.

## Điều kiện nghiệm thu

- Các lỗi đã nêu đều có regression test phù hợp và được xác minh, không chỉ đổi status để vượt qua màn hình.
- Buyer/supplier/admin dùng đúng profile ID; không có đường bỏ ownership vì null ID hoặc role kép.
- Thanh toán, giao hàng và finance_context đồng bộ; status đơn nhiều store nhất quán.
- PayOS kết quả không chắc chắn không làm tạo link/hoàn kho mù; retry/recovery không nhân đôi dữ liệu.
- FE chế độ thật không hiển thị dữ liệu mock khi API lỗi.
- Không thay kiến trúc, không mất dữ liệu cũ, không sửa migration đã phát hành.

## Tự rà plan

Đã phủ 7 mục audit theo thứ tự: quyền và trạng thái/payment → fulfillment → cancellation/stock → enum/FE → E2E. Các quyết định còn mở: chính sách DEPOSIT cho chuẩn bị/giao hàng; chi tiết trạng thái tổng hợp khi nhiều seller ở các giai đoạn khác nhau cần đối chiếu API spec trước khi implement. Plan không cấp quyền thay các business rule này, reset DB hoặc thực hiện giao dịch tiền thật.
