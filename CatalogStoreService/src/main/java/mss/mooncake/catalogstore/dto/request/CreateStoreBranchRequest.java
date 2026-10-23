package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStoreBranchRequest(
    @NotBlank(message = "Tên chi nhánh không được để trống")
    @Size(max = 255, message = "Tên chi nhánh tối đa 255 ký tự")
    String name,

    @NotBlank(message = "Địa chỉ chi nhánh không được để trống")
    @Size(max = 500, message = "Địa chỉ tối đa 500 ký tự")
    String addressLine,

    @Size(max = 100, message = "Phường/xã tối đa 100 ký tự")
    String ward,

    @Size(max = 100, message = "Quận/huyện tối đa 100 ký tự")
    String district,

    @NotBlank(message = "Tỉnh/thành phố không được để trống")
    @Size(max = 100, message = "Tỉnh/thành phố tối đa 100 ký tự")
    String city,

    @Size(max = 30, message = "Số điện thoại tối đa 30 ký tự")
    String phone,

    @Size(max = 255, message = "Giờ mở cửa tối đa 255 ký tự")
    String openingHours,

    Boolean primary
) { }
