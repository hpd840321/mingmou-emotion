package com.school.emotion.config;

import com.school.emotion.service.DataDirectoryScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class DataScanRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataScanRunner.class);
    private final DataDirectoryScanner scanner;

    public DataScanRunner(DataDirectoryScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public void run(String... args) {
        CompletableFuture.runAsync(() -> {
            log.info("Starting automatic data directory scan (async)...");
            long start = System.currentTimeMillis();
            var report = scanner.scanAll();
            long elapsed = (System.currentTimeMillis() - start) / 1000;
            if (report.error() != null) {
                log.warn("Scan completed with errors: {}", report.error());
            }
            log.info("Scan complete in {}s: {} total images, {} imported ({} skipped/failed)",
                    elapsed, report.total(), report.imported(), report.total() - report.imported());
        });
    }
}
