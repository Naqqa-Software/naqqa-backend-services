package com.naqqa.analytics.web;

import com.naqqa.analytics.export.ExportService;
import com.naqqa.analytics.model.SavedView;
import com.naqqa.analytics.model.ScheduledReport;
import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.reports.AuditService;
import com.naqqa.analytics.reports.SavedViewService;
import com.naqqa.analytics.reports.ScheduledReportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${naqqa.analytics.api-path:/api/analytics}")
public class AnalyticsManageController {

    private static final String H = AnalyticsAccess.IMPERSONATE_HEADER;

    private final AnalyticsWebSupport web;
    private final ExportService exports;
    private final SavedViewService views;
    private final ScheduledReportService scheduled;
    private final ImpersonationTokens tokens;

    public AnalyticsManageController(AnalyticsWebSupport web, ExportService exports, SavedViewService views, ScheduledReportService scheduled,
                                     ImpersonationTokens tokens) {
        this.web = web;
        this.exports = exports;
        this.views = views;
        this.scheduled = scheduled;
        this.tokens = tokens;
    }

    private AnalyticsAccess access() {
        return web.access();
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(Authentication auth, @RequestParam Map<String, String> params,
                                         @RequestHeader(name = H, required = false) String imp) {
        boolean impersonating = imp != null && !imp.isBlank();
        if (!impersonating) {
            access().require(auth, access().permissions().getExport());
        }
        boolean admin = !impersonating && access().isAdmin(auth);
        return exportFile(auth, params, imp, admin);
    }

    @GetMapping("/partner/export")
    public ResponseEntity<byte[]> partnerExport(Authentication auth, @RequestParam Map<String, String> params,
                                                @RequestHeader(name = H, required = false) String imp) {
        if (imp == null || imp.isBlank()) {
            access().require(auth, access().permissions().getExport());
        }
        return exportFile(auth, params, imp, false);
    }

    private ResponseEntity<byte[]> exportFile(Authentication auth, Map<String, String> params, String imp, boolean admin) {
        String report = ExportService.resolve(params.get("report"), !admin);
        String format = ExportService.format(params.get("format"));
        AnalyticsQuery q = admin ? access().admin(auth, web.parse(params, true))
                : access().partner(auth, web.parse(params, false), AnalyticsAccess.requestedCompany(params), imp);
        byte[] data = exports.export(report, q, format);
        web.log(auth, AuditService.EXPORT, report, q, imp == null || imp.isBlank() ? null : String.join(",", q.companyIds()), params);
        String file = ExportService.fileName(report, q, format);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(ExportService.contentType(format)))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(data);
    }

    @GetMapping("/saved-views")
    public List<SavedView> savedViews(Authentication auth, @RequestParam(name = "section", required = false) String section) {
        requireAny(auth);
        return views.list(access().userId(auth), section);
    }

    @PostMapping("/saved-views")
    public SavedView createSavedView(Authentication auth, @RequestBody SavedViewRequest body) {
        requireAny(auth);
        SavedView v = views.create(access().userId(auth), body == null ? null : body.name(), body == null ? null : body.section(),
                body == null ? null : body.query());
        web.log(auth, AuditService.SAVED_VIEW, v.getSection(), null, null, Map.of("id", v.getId()));
        return v;
    }

    @DeleteMapping("/saved-views/{id}")
    public ResponseEntity<Void> deleteSavedView(Authentication auth, @PathVariable String id) {
        requireAny(auth);
        views.delete(access().userId(auth), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/scheduled-reports")
    public List<ScheduledReport> scheduledReports(Authentication auth) {
        access().require(auth, access().permissions().getExport());
        return scheduled.list(access().userId(auth));
    }

    @PostMapping("/scheduled-reports")
    public ScheduledReport createScheduledReport(Authentication auth, @RequestBody ScheduledReportService.Request body) {
        access().require(auth, access().permissions().getExport());
        boolean admin = access().isAdmin(auth);
        if (!admin) {
            access().partnerCompanies(auth, null);
            String requested = body == null || body.query() == null ? null : body.query().get("companyId");
            if (requested != null) {
                access().partner(auth, web.parse(Map.of(), false), requested, null);
            }
        }
        ScheduledReport r = scheduled.create(access().userId(auth), !admin, body);
        web.log(auth, AuditService.SCHEDULE, r.getReport(), null, null, Map.of("id", r.getId(), "frequency", r.getFrequency()));
        return r;
    }

    @DeleteMapping("/scheduled-reports/{id}")
    public ResponseEntity<Void> deleteScheduledReport(Authentication auth, @PathVariable String id) {
        access().require(auth, access().permissions().getExport());
        scheduled.delete(access().userId(auth), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/impersonate/{companyId}")
    public ImpersonationTokens.Issued impersonate(Authentication auth, @PathVariable String companyId) {
        access().require(auth, access().permissions().getImpersonatePartner());
        ImpersonationTokens.Issued issued = tokens.issue(companyId, access().userId(auth));
        web.log(auth, AuditService.IMPERSONATE, "impersonate", null, companyId, Map.of("companyId", companyId));
        return issued;
    }

    @GetMapping("/audit")
    public AuditService.Page audit(Authentication auth, @RequestParam Map<String, String> params) {
        access().require(auth, access().permissions().getViewAll());
        AnalyticsQuery q = web.parse(params, true);
        Instant from = q.start();
        Instant to = q.endExclusive();
        int size = q.size();
        return web.audit().list(from, to, q.page(), size);
    }

    private void requireAny(Authentication auth) {
        access().require(auth, access().permissions().getViewAll(), access().permissions().getViewOwnCompany());
    }

    public record SavedViewRequest(String name, String section, Map<String, String> query) {
    }
}
