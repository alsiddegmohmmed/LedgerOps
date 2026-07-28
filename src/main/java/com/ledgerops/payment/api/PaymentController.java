package com.ledgerops.payment.api;

import com.ledgerops.payment.application.PaymentCreationResult;
import com.ledgerops.payment.application.PaymentCreationService;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/payments")
class PaymentController {

    private final PaymentCreationService paymentCreationService;

    PaymentController(PaymentCreationService paymentCreationService) {
        this.paymentCreationService = paymentCreationService;
    }

    @PostMapping
    ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request
            , HttpServletRequest httpRequest
    ) {
        PaymentCreationResult result;

        try {
            result = paymentCreationService.createPayment(
                    request.toCommand(),
                    AuthorizedRequestContextRequest.required(httpRequest),
                    AuthorizedRequestContextRequest.principal(httpRequest)
            );
        } catch (IllegalArgumentException exception) {
            throw new InvalidPaymentRequestException(
                    "The payment request contains an invalid value",
                    exception
            );
        }

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{paymentId}")
                .buildAndExpand(result.payment().id().value())
                .toUri();
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;

        return ResponseEntity.status(status)
                .location(location)
                .body(PaymentResponse.from(result.payment()));
    }
}
