package com.example.assetinspection.api;

import com.example.assetinspection.domain.InspectionRecord;
import com.example.assetinspection.domain.InspectionStatus;
import com.example.assetinspection.dto.CreateInspectionRecordRequest;
import com.example.assetinspection.dto.InspectionRecordPage;
import com.example.assetinspection.dto.StatusCount;
import com.example.assetinspection.dto.UpdateInspectionResultRequest;
import com.example.assetinspection.service.InspectionRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;

/** 正常业务接口；所有请求都先经过 TenantContextFilter。 */
@RestController
@RequestMapping("/api/inspection-records")
@Validated
public class InspectionRecordController {

    private final InspectionRecordService service;

    public InspectionRecordController(InspectionRecordService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InspectionRecord create(@Valid @RequestBody CreateInspectionRecordRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    public InspectionRecord findEventuallyConsistent(@PathVariable Long id,
                                                     @RequestParam
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                     LocalDate recordDate) {
        return service.findEventuallyConsistent(id, recordDate);
    }

    @GetMapping("/{id}/strong")
    public InspectionRecord findStrongInTransaction(@PathVariable Long id,
                                                    @RequestParam
                                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                    LocalDate recordDate) {
        return service.findStrongInTransaction(id, recordDate);
    }

    @GetMapping("/{id}/hint-primary")
    public InspectionRecord findStrongWithHint(@PathVariable Long id,
                                              @RequestParam
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                              LocalDate recordDate) {
        return service.findStrongWithHint(id, recordDate);
    }

    @GetMapping
    public InspectionRecordPage list(@RequestParam
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                     LocalDate startDate,
                                     @RequestParam
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                     LocalDate endDateExclusive,
                                     @RequestParam(required = false) InspectionStatus status,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                     LocalDate cursorDate,
                                     @RequestParam(required = false) Long cursorId,
                                     @RequestParam(required = false) Integer pageSize) {
        return service.list(startDate, endDateExclusive, status, cursorDate, cursorId, pageSize);
    }

    @PatchMapping("/{id}/result")
    public InspectionRecord updateResult(@PathVariable Long id,
                                         @RequestParam
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                         LocalDate recordDate,
                                         @Valid @RequestBody UpdateInspectionResultRequest request) {
        return service.updateResult(id, recordDate, request);
    }

    @GetMapping("/statistics")
    public List<StatusCount> statistics(@RequestParam
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                        LocalDate startDate,
                                        @RequestParam
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                        LocalDate endDateExclusive) {
        return service.statistics(startDate, endDateExclusive);
    }
}
