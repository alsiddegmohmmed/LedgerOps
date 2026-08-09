package com.ledgerops.tenancy.api;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.tenancy.application.OperationalContactService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/operational-contacts")
class OperationalContactController {

    private final OperationalContactService contacts;

    OperationalContactController(OperationalContactService contacts) {
        this.contacts = contacts;
    }

    @GetMapping
    List<OperationalContactResponse> current(
            @PathVariable UUID tenantId,
            HttpServletRequest request
    ) {
        return contacts.current(
                        TenantReference.from(tenantId),
                        AuthorizedRequestContextRequest.required(request)
                )
                .stream()
                .map(OperationalContactResponse::from)
                .toList();
    }

    @GetMapping("/{contactId}")
    OperationalContactResponse current(
            @PathVariable UUID tenantId,
            @PathVariable UUID contactId,
            HttpServletRequest request
    ) {
        return OperationalContactResponse.from(contacts.current(
                TenantReference.from(tenantId),
                contactId,
                AuthorizedRequestContextRequest.required(request)
        ));
    }

    @PutMapping("/{contactId}")
    ResponseEntity<OperationalContactResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID contactId,
            @Valid @RequestBody OperationalContactRequest body,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal actor = AuthorizedRequestContextRequest.principal(request);
        return ResponseEntity.ok(OperationalContactResponse.from(contacts.update(
                body.toCommand(
                        TenantReference.from(tenantId),
                        AuthorizedRequestContextRequest.required(request),
                        actor,
                        contactId
                )
        )));
    }
}
