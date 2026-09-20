package com.sumedha.commerce.checkout.client;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ForbiddenException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.common.core.exception.UnauthorizedException;
import com.sumedha.commerce.checkout.exception.DownstreamBadGatewayException;
import com.sumedha.commerce.checkout.exception.DownstreamTimeoutException;
import com.sumedha.commerce.checkout.exception.DownstreamUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

abstract class DownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(DownstreamClient.class);

    protected <T> T execute(Supplier<DownstreamApiResponse<T>> request, String serviceName) {
        try {
            DownstreamApiResponse<T> response = request.get();
            if (response == null || !response.success() || response.data() == null) {
                throw new InternalServerException(serviceName + " service returned an invalid response");
            }
            return response.data();
        } catch (RestClientResponseException exception) {
            throw translateResponseException(exception, serviceName);
        } catch (ResourceAccessException exception) {
            if (isTimeout(exception)) {
                log.warn("downstream timeout service={}", serviceName);
                throw new DownstreamTimeoutException(serviceName);
            }
            log.warn("downstream connection failure service={}", serviceName);
            throw new DownstreamUnavailableException(serviceName);
        } catch (RestClientException exception) {
            log.warn("downstream client failure service={} type={}", serviceName, exception.getClass().getSimpleName());
            throw new DownstreamUnavailableException(serviceName);
        }
    }

    private RuntimeException translateResponseException(RestClientResponseException exception, String serviceName) {
        return switch (exception.getStatusCode().value()) {
            case 400 -> new BadRequestException(serviceName + " service rejected the request");
            case 401 -> new UnauthorizedException(serviceName + " service rejected authentication");
            case 403 -> new ForbiddenException(serviceName + " service denied access");
            case 404 -> new ResourceNotFoundException(serviceName + " resource was not found");
            case 409 -> new ConflictException(serviceName + " service reported a conflict");
            default -> {
                log.warn("downstream HTTP failure service={} status={}", serviceName, exception.getStatusCode().value());
                yield new DownstreamBadGatewayException(serviceName);
            }
        };
    }

    private boolean isTimeout(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
