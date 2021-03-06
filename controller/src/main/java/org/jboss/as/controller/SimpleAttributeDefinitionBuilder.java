/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller;

import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * Provides a builder API for creating a {@link SimpleAttributeDefinition}.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class SimpleAttributeDefinitionBuilder extends AbstractAttributeDefinitionBuilder<SimpleAttributeDefinitionBuilder, SimpleAttributeDefinition> {

    public static SimpleAttributeDefinitionBuilder create(final String name, final ModelType type) {
        return new SimpleAttributeDefinitionBuilder(name, type);
    }

    public static SimpleAttributeDefinitionBuilder create(final String name, final ModelType type, final boolean allowNull) {
        return new SimpleAttributeDefinitionBuilder(name, type, allowNull);
    }

    public static SimpleAttributeDefinitionBuilder create(final SimpleAttributeDefinition basis) {
        return new SimpleAttributeDefinitionBuilder(basis);
    }

    /*
    "code" => {
        "type" => STRING,
        "description" => "Fully Qualified Name of the Security Vault Implementation.",
        "expressions-allowed" => false,
        "nillable" => true,
        "min-length" => 1L,
        "max-length" => 2147483647L,
        "access-type" => "read-write",
        "storage" => "configuration",
        "restart-required" => "no-services"
    },
    */
    public static SimpleAttributeDefinitionBuilder create(final String name, final ModelNode node) {
        ModelType type = node.get(ModelDescriptionConstants.TYPE).asType();
        boolean nillable = node.get(ModelDescriptionConstants.NILLABLE).asBoolean(true);
        boolean expressionAllowed = node.get(ModelDescriptionConstants.EXPRESSIONS_ALLOWED).asBoolean(false);
        ModelNode defaultValue = node.get(ModelDescriptionConstants.DEFAULT);
        return SimpleAttributeDefinitionBuilder.create(name, type, nillable)
                .setDefaultValue(defaultValue)
                .setAllowExpression(expressionAllowed);
    }

    public static SimpleAttributeDefinitionBuilder create(final String attributeName, final SimpleAttributeDefinition basis) {
        return new SimpleAttributeDefinitionBuilder(attributeName, basis);
    }

    private CapabilityReferenceRecorder referenceRecorder;

    public SimpleAttributeDefinitionBuilder(final String attributeName, final ModelType type) {
        this(attributeName, type, false);
    }

    public SimpleAttributeDefinitionBuilder(final String attributeName, final ModelType type, final boolean allowNull) {
        super(attributeName, type, allowNull);
        parser = AttributeParser.SIMPLE;
    }

    public SimpleAttributeDefinitionBuilder(final SimpleAttributeDefinition basis) {
        super(basis);
    }

    public SimpleAttributeDefinitionBuilder(final String attributeName, final SimpleAttributeDefinition basis) {
        super(attributeName, basis);
    }

    /**
     * Records that this attribute's value represents a reference to an instance of a
     * {@link org.jboss.as.controller.capability.RuntimeCapability#isDynamicallyNamed() dynamic capability}.
     * <p>
     * This method is a convenience method equivalent to calling
     * {@link #setCapabilityReference(CapabilityReferenceRecorder)}
     * passing in a {@link org.jboss.as.controller.CapabilityReferenceRecorder.DefaultCapabilityReferenceRecorder}
     * constructed using the parameters passed to this method.
     *
     * @param referencedCapability the name of the dynamic capability the dynamic portion of whose name is
     *                             represented by the attribute's value
     * @param dependentCapability the capability that depends on {@code referencedCapability}
     * @return the builder
     *
     * @see SimpleAttributeDefinition#addCapabilityRequirements(OperationContext, ModelNode)
     * @see SimpleAttributeDefinition#removeCapabilityRequirements(OperationContext, ModelNode)
     */
    public SimpleAttributeDefinitionBuilder setCapabilityReference(String referencedCapability, RuntimeCapability<?> dependentCapability) {
        return setCapabilityReference(referencedCapability, dependentCapability.getName(), dependentCapability.isDynamicallyNamed());
    }

    /**
     * Records that this attribute's value represents a reference to an instance of a
     * {@link org.jboss.as.controller.capability.RuntimeCapability#isDynamicallyNamed() dynamic capability}.
     * <p>
     * This method is a convenience method equivalent to calling
     * {@link #setCapabilityReference(CapabilityReferenceRecorder)}
     * passing in a {@link org.jboss.as.controller.CapabilityReferenceRecorder.DefaultCapabilityReferenceRecorder}
     * constructed using the parameters passed to this method.
     *
     * @param referencedCapability the name of the dynamic capability the dynamic portion of whose name is
     *                             represented by the attribute's value
     * @param dependentCapability the name of the capability that depends on {@code referencedCapability}
     * @param dynamicDependent {@code true} if {@code dependentCapability} is a dynamic capability, the dynamic
     *                                     portion of which comes from the name of the resource with which
     *                                     the attribute is associated
     * @return the builder
     *
     * @see SimpleAttributeDefinition#addCapabilityRequirements(OperationContext, ModelNode)
     * @see SimpleAttributeDefinition#removeCapabilityRequirements(OperationContext, ModelNode)
     */
    public SimpleAttributeDefinitionBuilder setCapabilityReference(String referencedCapability, String dependentCapability, boolean dynamicDependent) {
        referenceRecorder = new CapabilityReferenceRecorder.DefaultCapabilityReferenceRecorder(referencedCapability, dependentCapability, dynamicDependent);
        return this;
    }

    /**
     * Records that this attribute's value represents a reference to an instance of a
     * {@link org.jboss.as.controller.capability.RuntimeCapability#isDynamicallyNamed() dynamic capability} and assigns the
     * object that should be used to handle adding and removing capability requirements.
     *
     * @param referenceRecorder recorder to handle adding and removing capability requirements. May be {@code null}
     * @return the builder
     *
     * @see SimpleAttributeDefinition#addCapabilityRequirements(OperationContext, ModelNode)
     * @see SimpleAttributeDefinition#removeCapabilityRequirements(OperationContext, ModelNode)
     */
    public SimpleAttributeDefinitionBuilder setCapabilityReference(CapabilityReferenceRecorder referenceRecorder) {
        this.referenceRecorder = referenceRecorder;
        return this;
    }

    public SimpleAttributeDefinition build() {
        return new SimpleAttributeDefinition(this);
    }

    CapabilityReferenceRecorder getReferenceRecorder() {
        return referenceRecorder;
    }
}
