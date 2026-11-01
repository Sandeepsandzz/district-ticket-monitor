package com.district.monitor.repository;

import com.district.monitor.entity.AlertDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AlertDeliveryRepository extends JpaRepository<AlertDelivery,Long> {
    List<AlertDelivery> findByEventId(Long eventId);
}
