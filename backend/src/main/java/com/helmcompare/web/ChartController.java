package com.helmcompare.web;

import com.helmcompare.model.ChartRecord;
import com.helmcompare.service.ChartService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/charts")
public class ChartController {

    private final ChartService charts;

    public ChartController(ChartService charts) {
        this.charts = charts;
    }

    @GetMapping
    public List<ChartRecord> list(@RequestParam(defaultValue = "false") boolean includePortfolio) {
        return charts.list(includePortfolio);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChartRecord upload(@RequestParam("files") List<MultipartFile> files,
                              @RequestParam(required = false) String app,
                              @RequestParam(required = false) String version,
                              @RequestParam(required = false) ChartRecord.Environment environment,
                              @RequestParam(required = false) String revision,
                              @RequestParam(required = false) String notes,
                              @RequestParam(required = false) String supersedes) {
        String originalName = files.isEmpty() ? null : files.get(0).getOriginalFilename();
        return charts.upload(files, new ChartService.NewChart(app, version, environment, revision, notes, supersedes, null, originalName));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return Map.of("chart", charts.get(id), "items", charts.items(id));
    }

    @GetMapping("/{id}/source")
    public ChartService.SourceView source(@PathVariable String id, @RequestParam String path) {
        return charts.source(id, path);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        charts.delete(id);
    }
}
