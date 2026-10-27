# Chạy backend bằng Docker trên Windows

## 1. Chuẩn bị

- Java 21 và Docker Desktop sử dụng Linux containers/WSL 2.
- Mở Docker Desktop và đợi Docker Engine sẵn sàng.
- Tại thư mục `MoonCakeMarketplaceBE`, sao chép `.env.example` thành `.env` và điền các secret ngẫu nhiên, riêng biệt. `JWT_SECRET` và token giữa các service cần ít nhất 32 ký tự; mật khẩu SQL Server cần đủ độ mạnh.
- `.env` đã được bỏ qua trong Git và Docker build context. Giữ file này trên máy cá nhân.

Docker Desktop có thể lưu dữ liệu ở ổ D trong khi chương trình được cài ở ổ C. Ví dụ trên máy hiện tại: WSL distro/data nằm dưới `D:\DockerDesktopWSL`; chương trình Docker Desktop nằm trong thư mục ứng dụng của người dùng ở ổ C. Kiểm tra Docker Desktop → Settings → Resources để xác định đường dẫn dữ liệu. Giữ nguyên dữ liệu WSL và Docker volumes khi khởi động hệ thống.

## 2. Khởi động

```powershell
docker compose config --quiet
docker compose up -d --build
docker compose ps -a
```

Lần đầu cần tải image và tải dependency Maven. Compose thực hiện theo thứ tự:

```text
SQL Server khởi động
    → healthcheck thành công
    → db-init tạo database nếu chưa tồn tại
    → Spring Boot chạy Flyway để tạo/cập nhật bảng
    → Gateway phục vụ API
```

Các container `*-db-init` kết thúc với mã `0` sau khi tạo database; đây là trạng thái thành công. Chúng chạy lệnh idempotent `IF DB_ID(...) IS NULL CREATE DATABASE ...` khai báo trong `docker-compose.yml` (cùng nội dung với các script trong `docs/sqlserver`).

| Thành phần | Địa chỉ trên máy |
|---|---|
| API Gateway | `http://localhost:8080` |
| Identity | `http://localhost:8081` |
| Catalog & Store | `http://localhost:8082` |
| Commerce | `http://localhost:8083` |
| RFQ & Negotiation | `http://localhost:8084` |
| Identity SQL Server | `localhost,14331` — `mss_identity` |
| Catalog SQL Server | `localhost,14332` — `mss_catalog_store` |
| Commerce SQL Server | `localhost,14333` — `mss_commerce` |
| RFQ SQL Server | `localhost,14334` — `mss_rfq_negotiation` |

Frontend gọi API qua cổng `8080`. WebSocket chat sử dụng `ws://localhost:8080/ws/chat` trong môi trường local.

## 3. Kiểm tra và debug

```powershell
docker compose ps -a
docker compose logs --tail 100 rfq-negotiation-service
docker compose logs --tail 100 api-gateway
docker compose logs --tail 100 rfq-db-init
```

Nếu Docker báo không kết nối được `dockerDesktopLinuxEngine`, mở Docker Desktop và đợi engine hoạt động. Nếu DB có trạng thái `unhealthy`, kiểm tra log DB và mật khẩu trong `.env`. Nếu ứng dụng không khởi động, kiểm tra lỗi Flyway, biến cấu hình và cổng đang được sử dụng.

Mỗi DB có named volume riêng: `identity-db-data`, `catalog-db-data`, `commerce-db-data`, `rfq-db-data`; tên thực tế có thêm tiền tố Compose project. Dừng và chạy lại:

```powershell
docker compose stop
docker compose start
```

Giá trị mặc định `MSSQL_MEMORY_LIMIT_MB=1024` giới hạn bộ nhớ SQL Server để chạy bốn DB trên máy học tập; điều chỉnh theo tài nguyên máy nếu cần.

## 4. Kiểm thử TV4 trên SQL Server riêng

Sau khi DB container đã healthy, chạy:

```powershell
.\scripts\verify-tv4-sqlserver.ps1
```

Script tạo hai database kiểm thử riêng `mss_rfq_tv4_test` và `mss_commerce_tv4_test`, bật Flyway, rồi chạy test chấp nhận báo giá và tạo đơn từ báo giá. Mật khẩu được đọc từ `.env` và truyền qua biến môi trường của tiến trình. Các test giữ mock ở ranh giới gọi dịch vụ ngoài; chúng kiểm tra migration, persistence và nghiệp vụ trên SQL Server thật. Kiểm tra HTTP/WebSocket xuyên các container bằng các script ở mục 5.

## 5. Chạy demo HTTP và WebSocket qua Gateway

Chạy từ thư mục `MoonCakeMarketplaceBE` bằng **PowerShell 7 (`pwsh`)**; Java 21 cần có để chạy script chat. Chuẩn bị tài khoản thử nghiệm riêng, mới tạo:

- Một buyer có địa chỉ giao hàng thuộc chính tài khoản đó.
- Một supplier có hồ sơ và cửa hàng `ACTIVE` thuộc chính supplier. Hoàn thành điều kiện xác minh của Identity/Catalog trước khi tạo cửa hàng.
- Tùy chọn: một supplier thử nghiệm thứ hai để kiểm tra quyền xem báo giá/hội thoại của bên ngoài.

Lấy access token của các tài khoản này bằng API login. Script HTTP yêu cầu token, `storeId` và `addressId` đã có; việc tạo tài khoản/cửa hàng/địa chỉ được thực hiện trước đó.

**Script HTTP ghi dữ liệu vào các database ứng dụng Docker:** tạo RFQ, hai phiên bản báo giá, hội thoại và **một đơn demo chưa thanh toán**. Commerce lưu nghĩa vụ `DEPOSIT`/`REMAINING` ở trạng thái `PENDING`; script không thực hiện thanh toán. Với dữ liệu hiện tại của script, tổng đơn là `990000.00`, đặt cọc 30% là `297000.00`. Chỉ sử dụng tài khoản và dữ liệu demo riêng. Mỗi lần chạy tạo một bộ dữ liệu mới; script không tự xóa dữ liệu đã tạo, kể cả khi một bước kiểm tra sau đó thất bại.

Nhập JWT bằng prompt ẩn để không đưa giá trị token vào lịch sử lệnh:

```powershell
$env:TV4_BUYER_TOKEN = [System.Net.NetworkCredential]::new('', (Read-Host 'JWT buyer fixture' -AsSecureString)).Password
$env:TV4_SUPPLIER_TOKEN = [System.Net.NetworkCredential]::new('', (Read-Host 'JWT supplier fixture' -AsSecureString)).Password
$env:TV4_STORE_ID = Read-Host 'storeId của cửa hàng ACTIVE thuộc supplier fixture'
$env:TV4_ADDRESS_ID = Read-Host 'addressId thuộc buyer fixture'

$fixtureResult = .\scripts\smoke-tv4-http.ps1 -FixtureAccountsConfirmed
$fixtureResult

$env:TV4_CONVERSATION_ID = [string]$fixtureResult.conversationId
$env:TV4_FIXTURE_ACCOUNTS = 'true'
java scripts/SmokeTv4Chat.java
```

`-FixtureAccountsConfirmed` và `TV4_FIXTURE_ACCOUNTS=true` xác nhận bạn đã chuẩn bị tài khoản thử nghiệm. Nếu có supplier thử nghiệm thứ hai, đặt `TV4_OTHER_SUPPLIER_TOKEN` bằng cùng cách nhập ẩn trước khi chạy HTTP; script sẽ kiểm tra quyền truy cập của bên ngoài. `-BaseUrl` của script HTTP và `TV4_BASE_URL` của script chat mặc định là `http://localhost:8080`. `TV4_ORIGIN` mặc định là `http://localhost:5173` và phải nằm trong allowlist của Gateway/RFQ.

HTTP kiểm tra privacy RFQ, tổng tiền, revision, gửi lại yêu cầu và accept tạo đúng một đơn. Kết quả trả `PASS` cùng các ID, có thể lưu bằng `-ResultPath` khi cần. Chat dùng hội thoại và hai token từ lần HTTP đó, kiểm tra CONNECT/SUBSCRIBE/SEND qua Gateway, broadcast đến hai participant, gửi lặp giữ nguyên messageId và lịch sử REST chỉ lưu một tin nhắn.

Sau khi kiểm tra xong, xóa token khỏi môi trường phiên PowerShell:

```powershell
Remove-Item Env:TV4_BUYER_TOKEN, Env:TV4_SUPPLIER_TOKEN, Env:TV4_OTHER_SUPPLIER_TOKEN -ErrorAction SilentlyContinue
```
