/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl

import org.jetbrains.kotlin.descriptors.ScriptDescriptor
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrScriptSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.transformInPlace
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.SmartList

private val SCRIPT_ORIGIN = object : IrDeclarationOriginImpl("FIELD_FOR_OBJECT_INSTANCE") {}

class IrScriptImpl(
    override val symbol: IrScriptSymbol,
    override val name: Name
) : IrScript() {
    override val startOffset: Int get() = UNDEFINED_OFFSET
    override val endOffset: Int get() = UNDEFINED_OFFSET
    override var origin: IrDeclarationOrigin = SCRIPT_ORIGIN

    init {
        symbol.bind(this)
    }

    private var _parent: IrDeclarationParent? = null
    override var parent: IrDeclarationParent
        get() = _parent
            ?: throw UninitializedPropertyAccessException("Parent not initialized: $this")
        set(v) {
            _parent = v
        }

    override var annotations: List<IrConstructorCall> = SmartList()

    override val statements: MutableList<IrStatement> = mutableListOf()
    override val declarations: MutableList<IrDeclaration> get() = MutableDeclarationsView()

    override lateinit var thisReceiver: IrValueParameter

    override lateinit var baseClass: IrType
    override lateinit var explicitCallParameters: List<IrValueParameter>
    override lateinit var implicitReceivers: List<IrValueParameter>
    override lateinit var providedProperties: List<IrPropertySymbol>
    override var resultProperty: IrPropertySymbol? = null

    @ObsoleteDescriptorBasedAPI
    override val descriptor: ScriptDescriptor
        get() = symbol.descriptor

    override val factory: IrFactory
        get() = error("Create IrScriptImpl directly")

    init {
        symbol.bind(this)
    }

    override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R {
        return visitor.visitScript(this, data)
    }

    override fun <D> acceptChildren(visitor: IrElementVisitor<Unit, D>, data: D) {
        statements.forEach { it.accept(visitor, data) }
        thisReceiver.accept(visitor, data)
    }

    override fun <D> transformChildren(transformer: IrElementTransformer<D>, data: D) {
        statements.transformInPlace(transformer, data)
        thisReceiver = thisReceiver.transform(transformer, data)
    }

    private inner class MutableDeclarationsView : MutableList<IrDeclaration> {

        override val size: Int get() = statements.count { it is IrDeclaration }
        override fun contains(element: IrDeclaration): Boolean = statements.contains(element)
        override fun containsAll(elements: Collection<IrDeclaration>): Boolean = statements.containsAll(elements)
        override fun get(index: Int): IrDeclaration = getDeclarationsSequence().drop(index).first()
        override fun indexOf(element: IrDeclaration): Int = getDeclarationsSequence().indexOfFirst { it == element }
        override fun isEmpty(): Boolean = size == 0

        override fun iterator(): MutableIterator<IrDeclaration> = MutableDeclarationsIterator(getDeclarationsSequence().iterator())

        override fun lastIndexOf(element: IrDeclaration): Int = getDeclarationsSequence().indexOfLast { it == element }
        override fun add(element: IrDeclaration): Boolean = statements.add(element)

        override fun add(index: Int, element: IrDeclaration) {
            statements.add(projectIndex(index), element)
        }

        override fun addAll(index: Int, elements: Collection<IrDeclaration>): Boolean = statements.addAll(projectIndex(index), elements)
        override fun addAll(elements: Collection<IrDeclaration>): Boolean = statements.addAll(elements)

        override fun clear() {
            statements.removeIf { it is IrDeclaration }
        }

        override fun listIterator(): MutableListIterator<IrDeclaration> {
            TODO("Not yet implemented")
        }

        override fun listIterator(index: Int): MutableListIterator<IrDeclaration> {
            TODO("Not yet implemented")
        }

        override fun remove(element: IrDeclaration): Boolean = statements.remove(element)
        override fun removeAll(elements: Collection<IrDeclaration>): Boolean = statements.removeAll(elements)
        override fun removeAt(index: Int): IrDeclaration = statements.removeAt(projectIndex(index)) as IrDeclaration

        override fun retainAll(elements: Collection<IrDeclaration>): Boolean = statements.retainAll {
            it !is IrDeclaration || it in elements
        }

        override fun set(index: Int, element: IrDeclaration): IrDeclaration = statements.set(projectIndex(index), element) as IrDeclaration

        override fun subList(fromIndex: Int, toIndex: Int): MutableList<IrDeclaration> =
            statements.subList(projectIndex(fromIndex), projectIndex(toIndex)) as MutableList<IrDeclaration>

        private fun getDeclarationsSequence() = statements.asSequence().filterIsInstance<IrDeclaration>()

        private fun projectIndex(index: Int): Int {
            var declarationIndex = 0
            statements.forEachIndexed { projectedIndex, element ->
                if (element is IrDeclaration) {
                    if (index == declarationIndex) return@projectIndex projectedIndex
                    ++declarationIndex
                }
            }
            return -1
        }
    }

    private class MutableDeclarationsIterator(baseIterator: Iterator<IrDeclaration>) : Iterator<IrDeclaration> by baseIterator, MutableIterator<IrDeclaration> {
        override fun remove() {
            TODO("Not yet implemented")
        }
    }
}
