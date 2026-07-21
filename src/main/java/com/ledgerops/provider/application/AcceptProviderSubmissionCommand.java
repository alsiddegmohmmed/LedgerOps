package com.ledgerops.provider.application;

import com.ledgerops.messaging.api.ConsumerMessageStore;
import com.ledgerops.messaging.api.InboxResult;
import com.ledgerops.messaging.api.IncomingMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AcceptProviderSubmissionCommand {

    private final ConsumerMessageStore messages;
    private final ProviderWorkStore work;

    public AcceptProviderSubmissionCommand(
            ConsumerMessageStore messages,
            ProviderWorkStore work
    ) {
        this.messages = messages;
        this.work = work;
    }

    @Transactional
    public InboxResult accept(
            IncomingMessage incoming,
            ProviderSubmissionCommand command
    ) {
        if (incoming.tenantId() == null
                || !incoming.tenantId().equals(command.tenantId())
                || !incoming.messageId().equals(command.messageId())
                || !"SubmitPaymentToProvider".equals(incoming.messageType())) {
            throw new IllegalArgumentException(
                    "Inbox identity must match the tenant-owned Provider command"
            );
        }
        InboxResult result = messages.recordProcessed(incoming);
        if (result == InboxResult.PROCESSED) {
            work.createOrVerifySubmission(command);
        }
        return result;
    }
}
