package com.sumedha.commerce.checkout.client;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ForbiddenException;
import com.sumedha.commerce.common.core.exception.InternalServerException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.common.core.exception.UnauthorizedException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

abstract class DownstreamClient {

    protected <T> T execute(Supplier<DownstreamApiResponse<T>> request, String serviceName) {
        try {
            DownstreamApiResponse<T> response = request.get();
            if (response == null || !response.success() || response.data() == null) {
                throw new InternalServerException(serviceName + " service returned an invalid response");
            }
            return response.data();
        } catch (RestClientResponseException exception) {
            throw translateResponseException(exception, serviceName);
        } catch (RestClientException exception) {
            throw new InternalServerException(serviceName + " service is unavailable");
        }
    }

    private RuntimeException translateResponseException(RestClientResponseException exception, String serviceName) {
        return switch (exception.getStatusCode().value()) {
            case 400 -> new BadRequestException(serviceName + " service rejected the request");
            case 401 -> new UnauthorizedException(serviceName + " service rejected authentication");
            case 403 -> new ForbiddenException(serviceName + " service denied access");
            case 404 -> new ResourceNotFoundException(serviceName + " resource was not found");
            case 409 -> new ConflictException(serviceName + " service reported a conflict");
            default -> new InternalServerException(serviceName + " service failed");
        };
    }
}
