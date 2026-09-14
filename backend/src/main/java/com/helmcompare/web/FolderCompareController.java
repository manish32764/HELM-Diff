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
import java.util.Arrays;
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

    /** Two or three folders: side0, side1, side2 with optional label0… and secrets0… (AKeyless values JSON). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FolderCompare.Info create(@RequestParam(value = "side0", required = false) List<MultipartFile> side0,
                                     @RequestParam(value = "side1", required = false) List<MultipartFile> side1,
                                     @RequestParam(value = "side2", required = false) List<MultipartFile> side2,
                                     @RequestParam(required = false) String label0,
                                     @RequestParam(required = false) String label1,
                                     @RequestParam(required = false) String label2,
                                     @RequestParam(value = "secrets0", required = false) List<MultipartFile> secrets0,
                                     @RequestParam(value = "secrets1", required = false) List<MultipartFile> secrets1,
                                     @RequestParam(value = "secrets2", required = false) List<MultipartFile> secrets2) {
        return service.create(Arrays.asList(side0, side1, side2), Arrays.asList(label0, label1, label2),
                Arrays.asList(secrets0, secrets1, secrets2));
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

    /** @param sides the folders to compare, e.g. "0,2"; all when omitted */
    @GetMapping("/{id}/env")
    public FolderCompareService.EnvView env(@PathVariable String id, @RequestParam(defaultValue = "") String path,
                                            @RequestParam(defaultValue = "FILE") String scope,
                                            @RequestParam(required = false) String sides) {
        return service.envVars(id, path, scope, sides);
    }

    @GetMapping("/{id}/env/export")
    public ResponseEntity<byte[]> envExport(@PathVariable String id, @RequestParam(defaultValue = "") String path,
                                            @RequestParam(defaultValue = "FILE") String scope,
                                            @RequestParam(defaultValue = "xlsx") String format,
                                            @RequestParam(defaultValue = "false") boolean showSecrets,
                                            @RequestParam(required = false) String sides) {
        return download(export.envExport(id, path, scope, format, showSecrets, sides));
    }

    @GetMapping("/{id}/secret-values")
    public FolderCompareService.SecretValuesInfo secretValues(@PathVariable String id) {
        return service.secretValuesInfo(id);
    }

    /**
     * JSON with the actual value of each AKeyless path; several files are merged unless {@code replace}.
     *
     * @param side a side number, or all
     */
    @PostMapping(value = "/{id}/secret-values", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FolderCompareService.SecretValuesInfo uploadSecretValues(@PathVariable String id,
                                                                    @RequestParam("file") List<MultipartFile> file,
                                                                    @RequestParam(defaultValue = "all") String side,
                                                                    @RequestParam(defaultValue = "false") boolean replace) {
        return service.uploadSecretValues(id, side, file, replace);
    }

    @DeleteMapping("/{id}/secret-values")
    public void clearSecretValues(@PathVariable String id, @RequestParam(defaultValue = "all") String side) {
        service.clearSecretValues(id, side);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam(defaultValue = "xlsx") String format,
                                         @RequestParam(required = false) String sides) {
        return download(export.export(id, format, sides));
    }

    private static ResponseEntity<byte[]> download(ExportService.Export e) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(e.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(e.contentType()))
                .body(e.content());
    }
}
