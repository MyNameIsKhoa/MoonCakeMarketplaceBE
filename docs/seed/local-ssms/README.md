# Cài DB demo trên SQL Server local

Ba file `.txt` trong thư mục này mở bằng SSMS và chạy bằng **Execute (F5)**. Dùng **cùng một SQL Server instance** cho Identity, Catalog và Commerce (mặc định `localhost:1433`). Không chạy bộ này trên production hoặc SQL Server Docker tách riêng từng service.

1. **Dừng các Spring Boot service** trong IntelliJ. Chạy [`01_reset_3db.txt`](01_reset_3db.txt) trong SSMS: xóa và tạo lại `mss_identity`, `mss_catalog_store`, `mss_commerce`. **Mọi dữ liệu cũ, kể cả tài khoản, sẽ mất.** Bỏ qua bước này nếu không muốn reset.
2. Chạy lần lượt **Identity → Catalog → Commerce** trong IntelliJ. Đợi cả ba báo `Started`; Flyway tự tạo/cập nhật bảng. **Không chạy tay các file `V1__...sql`, `V2__...sql`**. Nếu DB đã có bảng từ lần trước, chỉ cần bảo đảm Flyway chạy thành công.
3. Chạy [`02_identity_demo.txt`](02_identity_demo.txt) trong SSMS để thêm 10 supplier demo.
4. Chạy [`03_catalog_demo.txt`](03_catalog_demo.txt) trong SSMS để thêm 10 gian hàng, 19 sản phẩm/variant và phí giao hàng demo. Cuối file có câu `SELECT` kiểm tra số lượng.

Nếu chạy hai file seed bằng `sqlcmd` thay vì SSMS, bắt buộc thêm `-f 65001` để đọc nội dung UTF-8; ví dụ `sqlcmd -S localhost -E -C -I -b -f 65001 -i docs/seed/local-ssms/02_identity_demo.txt` rồi chạy file `03_catalog_demo.txt` tương tự. Có thể chạy lại hai file sau khi đã lỡ dùng bảng mã mặc định: chúng chỉ bổ sung bản ghi demo còn thiếu và sửa tên/mô tả tiếng Việt của đúng các mã demo, không reset đơn hàng hoặc tồn kho.

Ảnh **không lưu trong SQL Server**: database chỉ lưu URL `/images/demo/...`; file ảnh nằm trong `MoonCakeMarketFrontend/public/images/demo`. Chạy FE bằng `npm run dev` để trình duyệt tải ảnh. Các supplier seed là tài khoản demo **không có mật khẩu đăng nhập**; muốn test đăng nhập, hãy tự đăng ký tài khoản mới qua Identity. Commerce không có đơn hàng seed — tạo đơn bằng giỏ hàng/checkout trên FE.

Nếu SSMS báo thiếu bảng hoặc cột, dừng và kiểm tra log Flyway của service tương ứng trước khi chạy seed tiếp. Các query này dành cho dữ liệu demo, không phải migration của ứng dụng.
