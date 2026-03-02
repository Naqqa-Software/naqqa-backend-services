package com.naqqa.entity.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/admin")
@RestController
public class AdminController {

//    private final LogService logService;
//
//    @GetMapping("/logs")
//    public Page<LogDto> getLogs(
//            @PageableDefault(size = 20) Pageable pageable,
//            @RequestParam(required = false) RoleEnum role,
//            @RequestParam(required = false) ActionType actionType,
//            @RequestParam(required = false) LogType logType,
//            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
//            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
//            @RequestParam(required = false, defaultValue = "createdAt") String sortBy,
//            @RequestParam(required = false, defaultValue = "DESC") SortDirection sortDir
//    ) {
//        return logService.findAllFiltered(pageable, role, actionType, logType, startDate, endDate, sortBy, sortDir);
//    }
}
