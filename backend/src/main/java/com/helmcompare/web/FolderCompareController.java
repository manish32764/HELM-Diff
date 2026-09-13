package com.helmcompare.web;

import com.helmcompare.export.ExportService;
import com.helmcompare.export.FolderCompareExport;
import com.helmcompare.model.FolderCompare;
import com.helmcompare.service.FolderCompareService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/folder-compares")
public class FolderCompareController {

    private final FolderCompareService service;
    private final FolderCompareExport export;

    public FolderCompareController(FolderCompareService service, FolderCompareExport export) {
        this.service = service;
        this.export = export;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FolderCompare.Info create(@RequestParam(value = "left", required = false) List<MultipartFile> left,
                                     @RequestParam(value = "right", required = false) List<MultipartFile> right,
                                     @RequestParam(required = false) String leftLabel,
                                     @RequestParam(required = false) String rightLabel) {
        return service.create(left, right, leftLabel, rightLabel);
    }

    @GetMapping
    public List<FolderCompare.Info> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public FolderCompare get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/{id}/file")
    public FolderCompareService.FileView file(@PathVariable String id, @RequestParam String path) {
        return service.file(id, path);
    }

    @GetMapping("/{id}/env")
    public FolderCompareService.EnvView env(@PathVariable String id, @RequestParam(defaultValue = "") String path,
                                            @RequestParam(defaultValue = "FILE") String scope) {
        return service.envVars(id, path, scope);
    }

    @GetMapping("/{id}/env/export")
    public ResponseEntity<byte[]> envExport(@PathVariable String id, @RequestParam(defaultValue = "") String path,
                                            @RequestParam(defaultValue = "FILE") String scope,
                                            @RequestParam(defaultValue = "xlsx") String format) {
        return download(export.envExport(id, path, scope, format));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam(defaultValue = "xlsx") String format) {
        return download(export.export(id, format));
    }

    private static ResponseEntity<byte[]> download(ExportService.Export e) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(e.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(e.contentType()))
                .body(e.content());
    }
}
