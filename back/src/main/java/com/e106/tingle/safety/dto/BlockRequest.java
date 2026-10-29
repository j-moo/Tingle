package com.e106.tingle.safety.dto;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Positive;

@BlockRequest.SingleContext
public record BlockRequest(@Positive Long proposalId, @Positive Long chatRoomId) {
	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.RUNTIME)
	@Constraint(validatedBy = SingleContextValidator.class)
	public @interface SingleContext {
		String message() default "Exactly one proposalId or chatRoomId is required";

		Class<?>[] groups() default {};

		Class<? extends Payload>[] payload() default {};
	}

	public static class SingleContextValidator implements ConstraintValidator<SingleContext, BlockRequest> {
		@Override
		public boolean isValid(BlockRequest request, ConstraintValidatorContext context) {
			return request == null || (request.proposalId() == null) != (request.chatRoomId() == null);
		}
	}
}
