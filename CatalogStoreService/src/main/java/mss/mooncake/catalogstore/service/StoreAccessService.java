package mss.mooncake.catalogstore.service;

import java.util.List;
import mss.mooncake.catalogstore.entity.StoreEntity;

/**
 * Kiểm tra quyền sở hữu store cho các use case catalog/promotion (TV2).
 * JWT không mang ROLE_SUPPLIER nên quyền supplier luôn xác minh qua Identity
 * theo userId.
 * Chỉ dùng nội bộ Catalog service; không trả StoreEntity ra REST.
 */
public interface StoreAccessService {

    /**
     * Trả store nếu user là supplier VERIFIED và sở hữu store.
     * Ném 404 nếu store không tồn tại, 400 nếu chưa là supplier VERIFIED, 403 nếu
     * không phải owner.
     *
     * @param userId userId từ JWT principal
     */
    StoreEntity requireOwnedStore(Long storeId, Long userId);

    /**
     * ID các store thuộc supplier của user (mọi trạng thái), phục vụ mine=true.
     * Trả danh sách rỗng nếu user chưa có hồ sơ supplier.
     */
    List<Long> findOwnedStoreIds(Long userId);

    /**
     * true nếu userId khác null và là owner của store; không ném lỗi khi user không
     * phải supplier.
     */
    boolean isOwner(Long storeId, Long userId);

    /** true nếu store tồn tại và đang ACTIVE (được hiển thị công khai). */
    boolean isPublic(Long storeId);
}
