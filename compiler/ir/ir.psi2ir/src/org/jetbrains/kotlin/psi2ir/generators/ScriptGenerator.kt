/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.generators

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.ScriptDescriptor
import org.jetbrains.kotlin.ir.assertCast
import org.jetbrains.kotlin.ir.builders.declarations.IrFunctionBuilder
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.lazy.IrLazyFunction
import org.jetbrains.kotlin.ir.descriptors.WrappedFunctionDescriptorWithContainerSource
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.util.varargElementType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.KtScriptInitializer
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.pureEndOffset
import org.jetbrains.kotlin.psi.psiUtil.pureStartOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffsetSkippingComments
import org.jetbrains.kotlin.psi2ir.deparenthesize
import org.jetbrains.kotlin.psi2ir.intermediate.createTemporaryVariableInBlock
import org.jetbrains.kotlin.psi2ir.intermediate.setExplicitReceiverValue
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.isSingleUnderscore

class ScriptGenerator(declarationGenerator: DeclarationGenerator) : DeclarationGeneratorExtension(declarationGenerator) {
    fun generateScriptDeclaration(ktScript: KtScript): IrDeclaration? {
        val descriptor = getOrFail(BindingContext.DECLARATION_TO_DESCRIPTOR, ktScript) as ScriptDescriptor

        val existedScripts = context.symbolTable.listExistedScripts()

        return context.symbolTable.declareScript(descriptor).buildWithScope { irScript ->

            // TODO: since script could reference instances of previous one their receivers have to be enlisted in its scope
            // Remove this code once script is no longer represented by Class
            existedScripts.forEach { context.symbolTable.introduceValueParameter(it.owner.thisReceiver) }

            val startOffset = ktScript.pureStartOffset
            val endOffset = ktScript.pureEndOffset

            fun makeReceiver(descriptor: ClassDescriptor): IrValueParameter {
                val receiverParameterDescriptor = descriptor.thisAsReceiverParameter
                return context.symbolTable.declareValueParameter(
                    startOffset, endOffset,
                    IrDeclarationOrigin.INSTANCE_RECEIVER,
                    receiverParameterDescriptor,
                    receiverParameterDescriptor.type.toIrType()
                ).also { it.parent = irScript }
            }

            irScript.thisReceiver = makeReceiver(descriptor)

            irScript.baseClass = descriptor.typeConstructor.supertypes.single().toIrType()
            irScript.implicitReceivers = descriptor.implicitReceivers.map(::makeReceiver)

            for (d in ktScript.declarations) {
                when (d) {
                    is KtScriptInitializer -> {
                        irScript.statements += BodyGenerator(
                            irScript.symbol,
                            context
                        ).generateExpressionBody(d.body!!).expression
                    }
                    is KtDestructuringDeclaration -> {
                        // copied with modifications from StatementGenerator.visitDestructuringDeclaration
                        // TODO: consider code deduplication
                        val bodyGenerator = BodyGenerator(irScript.symbol, context)
                        val statementGenerator = bodyGenerator.createStatementGenerator()
                        val irBlock = IrCompositeImpl(
                            d.startOffsetSkippingComments, d.endOffset,
                            context.irBuiltIns.unitType, IrStatementOrigin.DESTRUCTURING_DECLARATION
                        )
                        val ktInitializer = d.initializer!!
                        val initializerExpr = ktInitializer.deparenthesize().accept(statementGenerator, null).assertCast<IrExpression>()
                        val containerValue =
                            statementGenerator.scope.createTemporaryVariableInBlock(context, initializerExpr, irBlock, "container")

                        val callGenerator = CallGenerator(statementGenerator)

                        for ((index, ktEntry) in d.entries.withIndex()) {
                            val componentResolvedCall = getOrFail(BindingContext.COMPONENT_RESOLVED_CALL, ktEntry)

                            val componentSubstitutedCall = statementGenerator.pregenerateCall(componentResolvedCall)
                            componentSubstitutedCall.setExplicitReceiverValue(containerValue)

                            val componentVariable = getOrFail(BindingContext.VARIABLE, ktEntry)

                            // componentN for '_' SHOULD NOT be evaluated
                            if (componentVariable.name.isSpecial || ktEntry.isSingleUnderscore) continue

                            val irComponentCall = callGenerator.generateCall(
                                ktEntry.startOffsetSkippingComments, ktEntry.endOffset, componentSubstitutedCall,
                                IrStatementOrigin.COMPONENT_N.withIndex(index + 1)
                            )

                            val irComponentProperty =
                                PropertyGenerator(declarationGenerator).generateDestructuringDeclarationEntryAsPropertyDeclaration(
                                    ktEntry, irComponentCall
                                )
                            val irComponentBackingField = irComponentProperty.backingField!!

                            irScript.statements += irComponentProperty

                            val irComponentInitializer = IrSetFieldImpl(
                                ktEntry.startOffsetSkippingComments, ktEntry.endOffset,
                                irComponentBackingField.symbol,
                                context.irBuiltIns.unitType,
                                origin = null, superQualifierSymbol = null
                            ).apply {
                                value = irComponentCall
                                receiver = IrGetValueImpl(
                                    ktEntry.startOffsetSkippingComments, ktEntry.endOffset, irScript.thisReceiver.symbol
                                )
                            }
                            irBlock.statements.add(irComponentInitializer)
                        }
                        irScript.statements += irBlock
                    }
                    else -> irScript.statements += declarationGenerator.generateMemberDeclaration(d)!!
                }
            }

            descriptor.resultValue?.let { resultDescriptor ->
                val resultProperty = resultDescriptor.toIrProperty(
                    irScript,
                    startOffset,
                    endOffset,
                    IrDeclarationOrigin.SCRIPT_RESULT_PROPERTY
                )
                irScript.statements += resultProperty
                irScript.resultProperty = resultProperty.symbol
            }

            irScript.explicitCallParameters = descriptor.unsubstitutedPrimaryConstructor.valueParameters.map { valueParameterDescriptor ->
                valueParameterDescriptor.toIrValueParameter(startOffset, endOffset, IrDeclarationOrigin.SCRIPT_CALL_PARAMETER)
            }

            irScript.implicitReceivers = descriptor.implicitReceivers.map { implicitReceiver ->
                implicitReceiver.thisAsReceiverParameter.toIrValueParameter(
                    startOffset, endOffset, IrDeclarationOrigin.SCRIPT_IMPLICIT_RECEIVER
                )
            }

            irScript.providedProperties = descriptor.scriptProvidedProperties.map { providedProperty ->
                val irProperty = providedProperty.toIrProperty(
                    irScript,
                    startOffset,
                    endOffset,
                    IrDeclarationOrigin.SCRIPT_PROVIDED_PROPERTY
                )
                irScript.statements += irProperty
                irProperty.symbol
            }
        }
    }

    private fun PropertyDescriptor.toIrProperty(
        parent: IrDeclarationParent, startOffset: Int, endOffset: Int, origin: IrDeclarationOrigin
    ): IrProperty =
        context.symbolTable.declareProperty(
            startOffset, endOffset, origin,
            this,
            isDelegated = false
        ).also {
            it.parent = parent
            val type = this.returnType?.toIrType()
            it.addGetter {
                returnType = type!!
            }
        }

    private fun ParameterDescriptor.toIrValueParameter(startOffset: Int, endOffset: Int, origin: IrDeclarationOrigin) =
        context.symbolTable.declareValueParameter(
            startOffset, endOffset, origin,
            this,
            type.toIrType(),
            varargElementType?.toIrType()
        )
}

private inline fun IrProperty.addGetter(builder: IrFunctionBuilder.() -> Unit = {}): IrSimpleFunction =
    IrFunctionBuilder().run {
        name = Name.special("<get-${this@addGetter.name}>")
        builder()
        factory.buildFunction(this).also { getter ->
            this@addGetter.getter = getter
            getter.correspondingPropertySymbol = this@addGetter.symbol
            getter.parent = this@addGetter.parent
        }
    }

private fun IrFactory.buildFunction(builder: IrFunctionBuilder): IrSimpleFunction = with(builder) {
    val wrappedDescriptor = if (originalDeclaration is IrLazyFunction || containerSource != null)
        WrappedFunctionDescriptorWithContainerSource()
    else WrappedSimpleFunctionDescriptor()
    createFunction(
        startOffset, endOffset, origin,
        IrSimpleFunctionSymbolImpl(wrappedDescriptor),
        name, visibility, modality, returnType,
        isInline, isExternal, isTailrec, isSuspend, isOperator, isInfix, isExpect, isFakeOverride,
        containerSource,
    ).also {
        wrappedDescriptor.bind(it)
    }
}
