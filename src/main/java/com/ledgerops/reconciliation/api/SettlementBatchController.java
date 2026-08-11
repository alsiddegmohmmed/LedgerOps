package com.ledgerops.reconciliation.api;

import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.reconciliation.application.SettlementIngestionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/settlement-batches")
class SettlementBatchController {

    private final SettlementIngestionService ingestion;

    SettlementBatchController(SettlementIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<SettlementBatchHttpResponse> upload(
            @PathVariable UUID tenantId,
            @RequestParam String providerBatchReference,
            @RequestParam @DateTimeFormat(iso = DATE) LocalDate settlementPeriodStart,
            @RequestParam @DateTimeFormat(iso = DATE) LocalDate settlementPeriodEnd,
            @RequestParam(required = false) UUID supersedesBatchVersionId,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request
    ) throws java.io.IOException {
        var context = AuthorizedRequestContextRequest.required(request);
        var actor = AuthorizedRequestContextRequest.principal(request);
        var result = ingestion.upload(new SettlementIngestionService.UploadCommand(
                tenantId,
                providerBatchReference,
                settlementPeriodStart,
                settlementPeriodEnd,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getInputStream(),
                supersedesBatchVersionId,
                context,
                actor));
        return ResponseEntity.status(201).body(SettlementBatchHttpResponse.from(result));
    }

    @GetMapping
    SettlementBatchPage list(@PathVariable UUID tenantId, HttpServletRequest request) {
        return new SettlementBatchPage(ingestion.list(
                        tenantId, AuthorizedRequestContextRequest.required(request)).stream()
                .map(SettlementBatchHttpResponse::from)
                .toList());
    }

    @GetMapping("/{batchVersionId}")
    SettlementBatchHttpResponse find(
            @PathVariable UUID tenantId,
            @PathVariable UUID batchVersionId,
            HttpServletRequest request
    ) {
        return SettlementBatchHttpResponse.from(ingestion.find(
                tenantId, batchVersionId, AuthorizedRequestContextRequest.required(request)));
    }

    @GetMapping("/{batchVersionId}/validation-items")
    List<SettlementValidationItemSnapshot> validationItems(
            @PathVariable UUID tenantId,
            @PathVariable UUID batchVersionId,
            HttpServletRequest request
    ) {
        return ingestion.validationItems(
                tenantId, batchVersionId, AuthorizedRequestContextRequest.required(request));
    }

    @PostMapping("/{batchVersionId}/validate")
    SettlementBatchHttpResponse validate(
            @PathVariable UUID tenantId,
            @PathVariable UUID batchVersionId,
            HttpServletRequest request
    ) {
        return SettlementBatchHttpResponse.from(ingestion.validate(
                tenantId,
                batchVersionId,
                AuthorizedRequestContextRequest.required(request),
                AuthorizedRequestContextRequest.principal(request)));
    }

    @PostMapping("/{batchVersionId}/process")
    SettlementBatchHttpResponse process(
            @PathVariable UUID tenantId,
            @PathVariable UUID batchVersionId,
            @Valid @RequestBody SettlementProcessingHttpRequest body,
            HttpServletRequest request
    ) {
        return SettlementBatchHttpResponse.from(ingestion.process(
                tenantId,
                batchVersionId,
                body.confirmation(),
                AuthorizedRequestContextRequest.required(request),
                AuthorizedRequestContextRequest.principal(request)));
    }
}
