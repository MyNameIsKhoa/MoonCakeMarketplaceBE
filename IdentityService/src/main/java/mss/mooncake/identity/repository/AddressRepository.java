package mss.mooncake.identity.repository;

import java.util.List;
import java.util.Optional;
import mss.mooncake.identity.entity.AddressEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AddressRepository extends JpaRepository<AddressEntity, Long> {

    List<AddressEntity> findByCustomerIdOrderByDefaultAddressDescIdDesc(Long customerId);

    Optional<AddressEntity> findByIdAndCustomerId(Long id, Long customerId);

    Optional<AddressEntity> findFirstByCustomerIdOrderByIdAsc(Long customerId);

    long countByCustomerId(Long customerId);

    @Modifying
    @Query("UPDATE AddressEntity a SET a.defaultAddress = false WHERE a.customerId = :customerId")
    void resetDefaultAddressByCustomerId(@Param("customerId") Long customerId);
}
