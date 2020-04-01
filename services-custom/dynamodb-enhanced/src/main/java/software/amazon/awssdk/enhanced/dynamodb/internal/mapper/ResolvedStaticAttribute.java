/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.enhanced.dynamodb.internal.mapper;

import static software.amazon.awssdk.enhanced.dynamodb.internal.AttributeValues.nullAttributeValue;
import static software.amazon.awssdk.enhanced.dynamodb.internal.EnhancedClientUtils.isNullAttributeValue;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.enhanced.dynamodb.AttributeType;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticTableMetadata;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@SdkInternalApi
public final class ResolvedStaticAttribute<T, R> implements software.amazon.awssdk.enhanced.dynamodb.Attribute<T, R> {
    private final String attributeName;
    private final Function<T, AttributeValue> getAttributeMethod;
    private final BiConsumer<T, AttributeValue> updateItemMethod;
    private final StaticTableMetadata tableMetadata;
    private final AttributeType<R> attributeType;

    private ResolvedStaticAttribute(String attributeName,
                                    Function<T, AttributeValue> getAttributeMethod,
                                    BiConsumer<T, AttributeValue> updateItemMethod,
                                    StaticTableMetadata tableMetadata,
                                    AttributeType<R> attributeType) {
        this.attributeName = attributeName;
        this.getAttributeMethod = getAttributeMethod;
        this.updateItemMethod = updateItemMethod;
        this.tableMetadata = tableMetadata;
        this.attributeType = attributeType;
    }

    public static <T, R> ResolvedStaticAttribute<T, R> create(StaticAttribute<T, R> staticAttribute,
                                                           AttributeType<R> attributeType) {
        Function<T, AttributeValue> getAttributeValueWithTransform = item -> {
            R value = staticAttribute.getter().apply(item);
            return value == null ? nullAttributeValue() : attributeType.objectToAttributeValue(value);
        };

        // When setting a value on the java object, do not explicitly set nulls as this can cause an NPE to be thrown
        // if the target attribute type is a primitive.
        BiConsumer<T, AttributeValue> updateItemWithTransform = (item, attributeValue) -> {
            // If the attributeValue is nul, do not attempt to marshal
            if (isNullAttributeValue(attributeValue)) {
                return;
            }

            R value = attributeType.attributeValueToObject(attributeValue);

            if (value != null) {
                staticAttribute.setter().accept(item, value);
            }
        };

        StaticTableMetadata.Builder tableMetadataBuilder = StaticTableMetadata.builder();
        staticAttribute.tags().forEach(
            tag -> tag.modifyMetadata(staticAttribute.name(), attributeType.attributeValueType())
                      .accept(tableMetadataBuilder));

        return new ResolvedStaticAttribute<>(staticAttribute.name(),
                                             getAttributeValueWithTransform,
                                             updateItemWithTransform,
                                             tableMetadataBuilder.build(),
                                             attributeType);
    }

    /**
     * Return a transformed copy of this attribute that knows how to get/set from a different type of object given a
     * function that can convert the containing object itself. It does this by modifying the get/set functions of
     * type T to type S given a transformation function F(T) = S.
     * @param transform A function that converts the object storing the attribute from the source type to the
     *                  destination type.
     * @param createComponent A consumer to create a new instance of the component object when required. A null value
     *                       will bypass this logic.
     * @param <S> The type being transformed to.
     * @return A new Attribute that be contained by an object of type S.
     */
    public <S> ResolvedStaticAttribute<S, R> transform(Function<S, T> transform, Consumer<S> createComponent) {
        return new ResolvedStaticAttribute<>(
            attributeName,
            item -> {
                T otherItem = transform.apply(item);

                // If the containing object is null don't attempt to read attributes from it
                return otherItem == null ?
                    nullAttributeValue() : getAttributeMethod.apply(otherItem);
            },
            (item, value) -> {
                if (createComponent != null) {
                    // Lazily instantiate the component object once there is a value to write into it
                    createComponent.accept(item);
                }
                updateItemMethod.accept(transform.apply(item), value);
            },
            tableMetadata,
            attributeType);
    }

    @Override
    public String attributeName() {
        return attributeName;
    }

    @Override
    public AttributeType<R> attributeType() {
        return attributeType;
    }

    @Override
    public Function<T, AttributeValue> attributeGetterMethod() {
        return getAttributeMethod;
    }

    @Override
    public BiConsumer<T, AttributeValue> updateItemMethod() {
        return updateItemMethod;
    }

    @Override
    public StaticTableMetadata tableMetadata() {
        return tableMetadata;
    }
}
