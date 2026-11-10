# Cài DB demo trên SQL Server local

Các file `.txt` trong thư mục này mở bằng SSMS và chạy bằng **Execute (F5)**. Dùng **cùng một SQL Server instance** cho Identity, Catalog và Commerce (mặc định `localhost:1433`). Không chạy bộ này trên production hoặc SQL Server Docker tách riêng từng service.

1. **Dừng các Spring Boot service** trong IntelliJ. Chạy [`01_reset_3db.txt`](01_reset_3db.txt) trong SSMS: xóa và tạo lại `mss_identity`, `mss_catalog_store`, `mss_commerce`. **Mọi dữ liệu cũ, kể cả tài khoản, sẽ mất.** Bỏ qua bước này nếu không muốn reset.
2. Chạy lần lượt **Identity → Catalog → Commerce** trong IntelliJ. Đợi cả ba báo `Started`; Flyway tự tạo/cập nhật bảng. **Không chạy tay các file `V1__...sql`, `V2__...sql`**. Nếu DB đã có bảng từ lần trước, chỉ cần bảo đảm Flyway chạy thành công.
3. Chạy [`02_identity_demo.txt`](02_identity_demo.txt) trong SSMS để thêm 15 supplier demo.
4. Chạy [`03_catalog_demo.txt`](03_catalog_demo.txt) trong SSMS để thêm/cập nhật 15 gian hàng, chi nhánh chính, 34 sản phẩm/variant và phí giao hàng. Nếu đã có dữ liệu, file không reset đơn hàng hoặc tồn kho.
5. Chạy [`04_catalog_product_promotions.txt`](04_catalog_product_promotions.txt) trong SSMS để bỏ hậu tố `(demo)` ở tên hiển thị, bỏ `demo-` khỏi slug gian hàng, tắt 15 khuyến mãi seed cũ áp dụng toàn shop và tạo khuyến mãi **mua 1/2/3 tặng 1** cho một biến thể ở từng shop. Ví dụ mua 2 tặng 1 thì thêm 3 sản phẩm cùng biến thể vào giỏ, hệ thống tính tiền 2. File chạy lại được và in ra 15 dòng để kiểm tra. **Nếu chạy lại file 03 thì chạy lại file 04 sau cùng**, vì file 03 kích hoạt lại khuyến mãi seed cũ.

**DB đã có đủ 15 shop và sản phẩm:** chỉ cần chạy file 04, không chạy file reset. Nếu tên nhà cung cấp trong Identity còn hậu tố `(demo)`, chạy lại file 02 để cập nhật `company_name`; lịch sử đơn hàng đã chốt vẫn giữ nguyên snapshot cũ.

Nếu chạy các file seed bằng `sqlcmd` thay vì SSMS, bắt buộc thêm `-f 65001` để đọc nội dung UTF-8; ví dụ `sqlcmd -S localhost -E -C -I -b -f 65001 -i docs/seed/local-ssms/02_identity_demo.txt`, rồi chạy file 03 và 04 tương tự. Các mã `DEMO-...`, SKU, email `@demo.mss.invalid` và đường dẫn ảnh `/images/demo/...` vẫn được giữ để khớp dữ liệu cũ, tránh làm hỏng tham chiếu và lịch sử đơn hàng; chúng không phải tên hiển thị của shop.

Ảnh **không lưu trong SQL Server**: database chỉ lưu URL `/images/demo/...`; file ảnh nằm trong `MoonCakeMarketFrontend/public/images/demo`. Logo chữ SVG của Như Lan, Madame Hương, Brodard, KIDO's Bakery và Maison chỉ là hình minh họa, không phải logo chính thức. Tên thương hiệu trong seed chỉ phục vụ thử nghiệm, không xác nhận tài khoản nhà bán được các thương hiệu ủy quyền. Chạy FE bằng `npm run dev` để trình duyệt tải ảnh. Các supplier seed là tài khoản demo **không có mật khẩu đăng nhập**; muốn test đăng nhập, hãy tự đăng ký tài khoản mới qua Identity. Commerce không có đơn hàng seed — tạo đơn bằng giỏ hàng/checkout trên FE.

Nếu SSMS báo thiếu bảng hoặc cột, dừng và kiểm tra log Flyway của service tương ứng trước khi chạy seed tiếp. Các query này dành cho dữ liệu demo, không phải migration của ứng dụng.
