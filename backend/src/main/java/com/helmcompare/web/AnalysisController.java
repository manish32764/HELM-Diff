package com.helmcompare.web;

import com.helmcompare.export.ExportService;
import com.helmcompare.model.AnalysisRecord;
import com.helmcompare.model.Expectation;
import com.helmcompare.service.AnalysisService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {

    private final AnalysisService analyses;
    private final ExportService exports;

    public AnalysisController(AnalysisService analyses, ExportService exports) {
        this.analyses = analyses;
        this.exports = exports;
    }

    public record PairwiseRequest(String leftChartId, String rightChartId, String title) {
    }

    public record DiffCompareRequest(AnalysisService.PairSource historical, AnalysisService.PairSource current, String title) {
    }

    public record FourChartRequest(String a, String b, String c, String d, String title) {
    }

    public record PortfolioRequest(String portfolioId, String differenceSetId, List<Expectation> expectations, String title) {
    }

    public record ReviewRequest(String status, String comment) {
    }

    public record RerunRequest(Map<String, String> overrides) {
    }

    @GetMapping
    public List<AnalysisRecord.Summary> list() {
        return analyses.list();
    }

    @GetMapping("/{id}")
    public AnalysisRecord get(@PathVariable String id) {
        return analyses.get(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        analyses.delete(id);
    }

    @PostMapping("/pairwise")
    public AnalysisRecord pairwise(@RequestBody PairwiseRequest req) {
        return analyses.pairwise(req.leftChartId(), req.rightChartId(), req.title(), null);
    }

    @PostMapping("/diff-compare")
    public AnalysisRecord diffCompare(@RequestBody DiffCompareRequest req) {
        return analyses.diffCompare(req.historical(), req.current(), req.title());
    }

    @PostMapping("/four-chart")
    public AnalysisRecord fourChart(@RequestBody FourChartRequest req) {
        return analyses.fourChart(req.a(), req.b(), req.c(), req.d(), req.title());
    }

    @PostMapping("/portfolio")
    public AnalysisRecord portfolio(@RequestBody PortfolioRequest req) {
        return analyses.portfolio(req.portfolioId(), req.differenceSetId(), req.expectations(), req.title(), null);
    }

    @GetMapping("/{id}/expectations")
    public List<Expectation> expectations(@PathVariable String id) {
        return analyses.expectations(id);
    }

    @PutMapping("/{id}/reviews/{itemId}")
    public AnalysisRecord review(@PathVariable String id, @PathVariable String itemId, @RequestBody ReviewRequest req) {
        return analyses.review(id, itemId, req.status(), req.comment());
    }

    @PostMapping("/{id}/rerun")
    public AnalysisRecord rerun(@PathVariable String id, @RequestBody(required = false) RerunRequest req) {
        return analyses.rerun(id, req == null ? null : req.overrides());
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam(defaultValue = "xlsx") String format) {
        AnalysisRecord rec = analyses.get(id);
        ExportService.Export export = exports.export(rec, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(export.contentType()))
                .body(export.content());
    }
}
