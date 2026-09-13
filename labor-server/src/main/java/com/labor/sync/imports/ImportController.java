package com.labor.sync.imports;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labor.sync.common.ApiResponse;
import com.labor.sync.common.PageResponse;
import com.labor.sync.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin/imports")
public class ImportController {
    private final ImportService importService;
    private final ImportBatchRepository batchRepository;
    private final ImportRowRepository rowRepository;
    private final ImportErrorRepository errorRepository;
    private final ObjectMapper objectMapper;
    private final ImportTemplateService importTemplateService;

    @Value("${app.import.template-path}")
    private String templatePath;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('import:upload')")
    public ApiResponse<BatchView> upload(@RequestPart("file") MultipartFile file,
                                         @RequestParam(defaultValue = "ALL") ImportScope scope) {
        return ApiResponse.ok(BatchView.from(importService.upload(file, scope)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('import:view')")
    public ApiResponse<PageResponse<BatchView>> list(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        var result = batchRepository.findByProjectId(WorkspaceContext.projectId(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Direction.DESC, "createdAt")));
        return ApiResponse.ok(PageResponse.from(result, BatchView::from));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('import:view')")
    public ApiResponse<BatchView> get(@PathVariable Long id) {
        return ApiResponse.ok(BatchView.from(importService.get(id)));
    }

    @GetMapping("/{id}/rows")
    @PreAuthorize("hasAuthority('import:view')")
    public ApiResponse<PageResponse<RowView>> rows(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        importService.get(id);
        var result = rowRepository.findByBatchId(id, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.ASC, "id")));
        return ApiResponse.ok(PageResponse.from(result, this::rowView));
    }

    @GetMapping("/{id}/errors")
    @PreAuthorize("hasAuthority('import:view')")
    public ApiResponse<PageResponse<ErrorView>> errors(@PathVariable Long id,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "50") int size) {
        importService.get(id);
        var result = errorRepository.findByBatchId(id, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by("sheetName", "rowNumber", "id")));
        return ApiResponse.ok(PageResponse.from(result, this::errorView));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('import:submit')")
    public ApiResponse<BatchView> submit(@PathVariable Long id) {
        return ApiResponse.ok(BatchView.from(importService.submit(id)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('import:review')")
    public ApiResponse<BatchView> approve(@PathVariable Long id, @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.ok(BatchView.from(importService.approve(id, request.comment())));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('import:review')")
    public ApiResponse<BatchView> reject(@PathVariable Long id, @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.ok(BatchView.from(importService.reject(id, request.comment())));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('import:publish')")
    public ApiResponse<BatchView> publish(@PathVariable Long id) {
        return ApiResponse.ok(BatchView.from(importService.publish(id)));
    }

    @GetMapping("/{id}/errors/export")
    @PreAuthorize("hasAuthority('import:export')")
    public ResponseEntity<byte[]> exportErrors(@PathVariable Long id) throws Exception {
        ImportBatch batch = importService.get(id);
        List<ImportError> errors = errorRepository.findByBatchIdOrderBySheetNameAscRowNumberAsc(id);
        byte[] bytes;
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("错误明细");
            Row header = sheet.createRow(0);
            String[] titles = {"工作表", "行号", "字段", "错误代码", "错误说明", "原始值"};
            CellStyle style = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            for (int i = 0; i < titles.length; i++) {
                header.createCell(i).setCellValue(titles[i]);
                header.getCell(i).setCellStyle(style);
            }
            for (int i = 0; i < errors.size(); i++) {
                ImportError error = errors.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(error.getSheetName());
                row.createCell(1).setCellValue(error.getRowNumber());
                row.createCell(2).setCellValue(error.getFieldName());
                row.createCell(3).setCellValue(error.getErrorCode());
                row.createCell(4).setCellValue(error.getMessage());
                String rawValue = errorValue(error);
                row.createCell(5).setCellValue(rawValue == null ? "" : rawValue);
            }
            sheet.setColumnWidth(0, 18 * 256);
            sheet.setColumnWidth(1, 10 * 256);
            sheet.setColumnWidth(2, 24 * 256);
            sheet.setColumnWidth(3, 24 * 256);
            sheet.setColumnWidth(4, 48 * 256);
            sheet.setColumnWidth(5, 28 * 256);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        String filename = "导入错误_" + batch.getId() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('import:view')")
    public ResponseEntity<byte[]> template(@RequestParam(defaultValue = "ALL") ImportScope scope) throws Exception {
        Path path = locateTemplate();
        byte[] bytes = Files.readAllBytes(path);
        String filename = path.getFileName().toString();
        bytes = importTemplateService.prepare(bytes, scope, WorkspaceContext.proCode());
        if (scope != ImportScope.ALL) {
            filename = "劳务实名制" + scope.displayName() + "导入模板_V1.0.xlsx";
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    private Path locateTemplate() {
        Path direct = Path.of(templatePath).toAbsolutePath().normalize();
        if (Files.exists(direct)) return direct;
        Path parent = Path.of("..").resolve(templatePath).toAbsolutePath().normalize();
        if (Files.exists(parent)) return parent;
        throw new IllegalStateException("导入模板不存在: " + templatePath);
    }

    private RowView rowView(ImportRow row) {
        Map<String, String> normalized = importService.displayNormalized(row);
        Map<String, String> raw = new LinkedHashMap<>(parse(row.getRawJson()));
        raw.replaceAll((field, value) -> containsMask(value) ? normalized.getOrDefault(field, value) : value);
        return new RowView(row.getId(), row.getSheetName(), row.getRowNumber(), row.getEntityType(),
                row.getBusinessKey(), raw, normalized,
                row.getChangeType(), row.getValidationStatus());
    }

    private ErrorView errorView(ImportError error) {
        return new ErrorView(error.getId(), error.getSheetName(), error.getRowNumber(), error.getFieldName(),
                error.getErrorCode(), error.getMessage(), errorValue(error));
    }

    private String errorValue(ImportError error) {
        String value = error.getRawValue();
        if (!containsMask(value) || error.getImportRow() == null) return value;
        return importService.displayNormalized(error.getImportRow()).getOrDefault(error.getFieldName(), value);
    }

    private boolean containsMask(String value) {
        return value != null && value.contains("*");
    }

    private Map<String, String> parse(String json) {
        try {
            String source = json != null && json.startsWith("\"")
                    ? objectMapper.readValue(json, String.class)
                    : json;
            return objectMapper.readValue(source, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    public record ReviewRequest(@Size(max = 1000) String comment) {}

    public record BatchView(Long id, String fileName, String fileHash, String uploadedBy, ImportScope scope,
                            ImportBatchStatus status,
                            int totalCount, int validCount, int errorCount, int newCount, int updatedCount,
                            int unchangedCount, String reviewComment, String reviewedBy, Instant reviewedAt,
                            Instant publishedAt, Instant createdAt) {
        static BatchView from(ImportBatch batch) {
            return new BatchView(batch.getId(), batch.getFileName(), batch.getFileHash(), batch.getUploadedBy(),
                    batch.getScope(), batch.getStatus(), batch.getTotalCount(), batch.getValidCount(), batch.getErrorCount(),
                    batch.getNewCount(), batch.getUpdatedCount(), batch.getUnchangedCount(), batch.getReviewComment(),
                    batch.getReviewedBy(), batch.getReviewedAt(), batch.getPublishedAt(), batch.getCreatedAt());
        }
    }

    public record RowView(Long id, String sheet, int row, String entityType, String businessKey,
                          Map<String, String> raw, Map<String, String> normalized,
                          ChangeType changeType, RowValidationStatus validationStatus) {}

    public record ErrorView(Long id, String sheet, int row, String field, String errorCode,
                            String message, String rawValue) {}
}
