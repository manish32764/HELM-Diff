package com.helmcompare.web;

import com.helmcompare.model.PortfolioRecord;
import com.helmcompare.service.DemoDataService;
import com.helmcompare.service.PortfolioService;
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
@RequestMapping("/api")
public class PortfolioController {

    private final PortfolioService portfolios;
    private final DemoDataService demo;

    public PortfolioController(PortfolioService portfolios, DemoDataService demo) {
        this.portfolios = portfolios;
        this.demo = demo;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/portfolios")
    public List<PortfolioRecord> list() {
        return portfolios.list();
    }

    @GetMapping("/portfolios/{id}")
    public PortfolioRecord get(@PathVariable String id) {
        return portfolios.get(id);
    }

    @PostMapping(value = "/portfolios", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PortfolioRecord upload(@RequestParam("files") List<MultipartFile> files,
                                  @RequestParam(required = false) String name,
                                  @RequestParam(required = false) String version) {
        return portfolios.upload(files, name, version);
    }

    @DeleteMapping("/portfolios/{id}")
    public void delete(@PathVariable String id) {
        portfolios.delete(id);
    }

    @GetMapping("/portfolios/{id}/search")
    public List<PortfolioService.SearchHit> search(@PathVariable String id, @RequestParam String differenceSetId,
                                                   @RequestParam String entryId) {
        return portfolios.search(id, differenceSetId, entryId);
    }

    @PostMapping("/demo/seed")
    public Map<String, Object> seed() {
        return demo.seed();
    }
}
