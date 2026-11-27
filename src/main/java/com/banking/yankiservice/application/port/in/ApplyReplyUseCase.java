package com.banking.yankiservice.application.port.in;

import com.banking.yankiservice.domain.model.vo.InboundReply;
import reactor.core.publisher.Mono;

/**
 * Applies an answer of Card Service or Transaction Service to the wallet or the payment that waits
 * for it.
 */
public interface ApplyReplyUseCase {

  Mono<Void> execute(InboundReply reply);
}
