/*
 * Copyright 2014-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.repository.core.support;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.CollectionFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.data.repository.util.NullableWrapper;
import org.springframework.data.repository.util.QueryExecutionConverters;
import org.springframework.data.repository.util.ReactiveWrapperConverters;
import org.springframework.lang.Nullable;

/**
 * Simple domain service to convert query results into a dedicated type.
 *
 * @author Oliver Gierke
 * @author Mark Paluch
 * @author Jens Schauder
 */
class QueryExecutionResultHandler {

	private static final TypeDescriptor WRAPPER_TYPE = TypeDescriptor.valueOf(NullableWrapper.class);

	private final GenericConversionService conversionService;

	/**
	 * Creates a new {@link QueryExecutionResultHandler}.
	 */
	public QueryExecutionResultHandler() {

		GenericConversionService conversionService = new DefaultConversionService();
		QueryExecutionConverters.registerConvertersIn(conversionService);
		conversionService.removeConvertible(Object.class, Object.class);

		this.conversionService = conversionService;
	}

	/**
	 * Post-processes the given result of a query invocation to match the return type of the given method.
	 *
	 * @param result can be {@literal null}.
	 * @param method must not be {@literal null}.
	 * @return
	 */
	@Nullable
	public Object postProcessInvocationResult(@Nullable Object result, Method method) {

		if (!processingRequired(result, method.getReturnType())) {
			return result;
		}

		MethodParameter parameter = new MethodParameter(method, -1);

		return postProcessInvocationResult(result, 0, parameter);

	}

	/**
	 * Post-processes the given result of a query invocation to the given type.
	 *
	 * @param result can be {@literal null}.
	 * @param nestingLevel
	 * @param parameter must not be {@literal null}.
	 * @return
	 */
	@Nullable
	Object postProcessInvocationResult(@Nullable Object result, int nestingLevel, MethodParameter parameter) {

		TypeDescriptor returnTypeDescriptor = TypeDescriptor.nested(parameter, nestingLevel);

		if (returnTypeDescriptor == null) {
			return result;
		}

		Class<?> expectedReturnType = returnTypeDescriptor.getType();

		result = unwrapOptional(result);

		if (QueryExecutionConverters.supports(expectedReturnType)) {

			// For a wrapper type, try nested resolution first
			result = postProcessInvocationResult(result, nestingLevel + 1, parameter);

			if (conversionRequired(WRAPPER_TYPE, returnTypeDescriptor)) {
				return conversionService.convert(new NullableWrapper(result), returnTypeDescriptor);
			}

			if (result != null) {

				TypeDescriptor source = TypeDescriptor.valueOf(result.getClass());

				if (conversionRequired(source, returnTypeDescriptor)) {
					return conversionService.convert(result, returnTypeDescriptor);
				}
			}
		}

		if (result != null) {

			if (ReactiveWrapperConverters.supports(expectedReturnType)) {
				return ReactiveWrapperConverters.toWrapper(result, expectedReturnType);
			}

			return conversionService.canConvert(TypeDescriptor.forObject(result), returnTypeDescriptor)
					? conversionService.convert(result, returnTypeDescriptor)
					: result;
		}

		return Map.class.equals(expectedReturnType) //
				? CollectionFactory.createMap(expectedReturnType, 0) //
				: null;
	}

	/**
	 * Returns whether the configured {@link ConversionService} can convert between the given {@link TypeDescriptor}s and
	 * the conversion will not be a no-op.
	 *
	 * @param source
	 * @param target
	 * @return
	 */
	private boolean conversionRequired(TypeDescriptor source, TypeDescriptor target) {

		return conversionService.canConvert(source, target) //
				&& !conversionService.canBypassConvert(source, target);
	}

	/**
	 * Unwraps the given value if it's a JDK 8 {@link Optional}.
	 *
	 * @param source can be {@literal null}.
	 * @return
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	private static Object unwrapOptional(@Nullable Object source) {

		if (source == null) {
			return null;
		}

		return Optional.class.isInstance(source) //
				? Optional.class.cast(source).orElse(null) //
				: source;
	}

	/**
	 * Returns whether we have to process the given source object in the first place.
	 * 
	 * @param source can be {@literal null}.
	 * @param targetType must not be {@literal null}.
	 * @return
	 */
	private static boolean processingRequired(@Nullable Object source, Class<?> targetType) {

		return !targetType.isInstance(source) //
				|| source == null //
				|| Collection.class.isInstance(source);
	}
}
