package com.example.assetinspection.api;

import com.example.assetinspection.domain.InspectionRecord;
import com.example.assetinspection.dto.RouteExpectation;
import com.example.assetinspection.service.ExpectedRouteService;
import com.example.assetinspection.service.InspectionRecordService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 仅供本地教学的路由推演和反例接口。 */
@RestController
@RequestMapping("/api/debug/routes")
public class RoutingExperimentController {

    private final ExpectedRouteService expectedRouteService;
    private final InspectionRecordService inspectionRecordService;

    public RoutingExperimentController(ExpectedRouteService expectedRouteService,
                                       InspectionRecordService inspectionRecordService) {
        this.expectedRouteService = expectedRouteService;
        this.inspectionRecordService = inspectionRecordService;
    }

    @GetMapping("/expected")
    public RouteExpectation expected(@RequestParam Long tenantId,
                                     @RequestParam
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                     LocalDate startDate,
                                     @RequestParam
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                     LocalDate endDateExclusive) {
        return expectedRouteService.explain(tenantId, startDate, endDateExclusive);
    }

    @GetMapping("/unsafe-without-date/{id}")
    public InspectionRecord unsafeWithoutDate(@PathVariable Long id,
                                              @RequestParam Long tenantId) {
        return inspectionRecordService.unsafeFindWithoutRecordDate(tenantId, id);
    }
}
