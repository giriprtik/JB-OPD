package com.hospital.lab.repository;

import com.hospital.lab.model.LabOrder;
import com.hospital.lab.model.LabOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LabOrderRepository extends JpaRepository<LabOrder, Long> {
    List<LabOrder> findByPatientMrn(String patientMrn);
    List<LabOrder> findByVisitId(Long visitId);
    List<LabOrder> findByOrderStatus(LabOrderStatus status);
    List<LabOrder> findByOrderStatusInOrderByOrderedAtAsc(Collection<LabOrderStatus> statuses);
    Optional<LabOrder> findByRequestId(UUID requestId);
}
