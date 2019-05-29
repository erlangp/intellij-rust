/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.hints

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.parameterInfo.*
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.util.containers.nullize
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*

private const val WHERE_PREFIX = "where "

class RsGenericParameterInfoHandler : RsAsyncParameterInfoHandler<RsTypeArgumentList, HintLine>() {
    override fun findTargetElement(file: PsiFile, offset: Int): RsTypeArgumentList? =
        findExceptColonColon(file, offset)

    override fun calculateParameterInfo(element: RsTypeArgumentList): Array<HintLine>? {
        val parent = element.parent
        val genericDeclaration = if (parent is RsMethodCall || parent is RsPath) {
            parent.reference?.resolve() as? RsGenericDeclaration ?: return null
        } else {
            return null
        }
        val typesWithBounds = genericDeclaration.typeParameters.nullize() ?: return null
        return listOfNotNull(firstLine(typesWithBounds), secondLine(typesWithBounds)).toTypedArray()
    }

    override fun showParameterInfo(element: RsTypeArgumentList, context: CreateParameterInfoContext) {
        context.highlightedElement = null
        super.showParameterInfo(element, context)
    }

    override fun updateParameterInfo(parameterOwner: RsTypeArgumentList, context: UpdateParameterInfoContext) {
        if (context.parameterOwner != parameterOwner) {
            context.removeHint()
            return
        }
        val curParam =
            ParameterInfoUtils.getCurrentParameterIndex(parameterOwner.node, context.offset, RsElementTypes.COMMA)
        context.setCurrentParameter(curParam)
    }

    override fun updateUI(p: HintLine, context: ParameterInfoUIContext) {
        context.setupUIComponentPresentation(
            p.presentText,
            p.getRange(context.currentParameterIndex).startOffset,
            p.getRange(context.currentParameterIndex).endOffset,
            false, // define whole hint line grayed
            false,
            false, // define grayed part of args before highlight
            context.defaultParameterColor
        )
    }

    override fun getParametersForLookup(item: LookupElement?, context: ParameterInfoContext?): Array<Any>? = null

    override fun couldShowInLookup() = false

    // to avoid hint on :: before <>
    private fun findExceptColonColon(file: PsiFile, offset: Int): RsTypeArgumentList? {
        val element = file.findElementAt(offset) ?: return null
        if (element.elementType == RsElementTypes.COLONCOLON) return null
        return element.ancestorStrict()
    }
}

/**
 * Stores the text representation and ranges for parameters
 */
class HintLine(
    val presentText: String,
    private val ranges: List<TextRange>
) {
    fun getRange(index: Int): TextRange = if (index !in 0 until ranges.size) TextRange.EMPTY_RANGE else ranges[index]
}

/**
 * Calculates the text representation and ranges for parameters
 */
private fun firstLine(params: List<RsTypeParameter>): HintLine {
    val splited = params.map { param ->
        param.name ?: return@map ""
        val qSizedBound = if (!param.isSized) listOf("?Sized") else emptyList()
        val declaredBounds = param.bounds
            // `?Sized`, if needed, in separate val, `Sized` shouldn't be shown
            .filter { it.bound.traitRef?.resolveToBoundTrait?.element?.isSizedTrait == false }
            .mapNotNull { it.bound.traitRef?.path?.text }
        val allBounds = qSizedBound + declaredBounds
        param.name + (allBounds.nullize()?.joinToString(prefix = ": ", separator = " + ") ?: "")
    }
    return HintLine(splited.joinToString(), splited.indices.map { splited.calculateRange(it) })
}

/**
 * Not null, when complicated parts of where exists,
 * i.e. `where i32: SomeTrait<T>` or `where Option<T>: SomeTrait`
 */
private fun secondLine(params: List<RsTypeParameter>): HintLine? {
    val owner = params.getOrNull(0)?.parent?.parent as? RsGenericDeclaration
    val wherePreds = owner?.whereClause?.wherePredList.orEmpty()
        // retain specific preds
        .filterNot {
            params.contains((it.typeReference?.typeElement as? RsBaseType)?.path?.reference?.resolve())
        }
    val splited = wherePreds.map { it.text }
    return if (splited.isNotEmpty()) {
        HintLine(splited.joinToString(prefix = WHERE_PREFIX),
            splited.indices.map { TextRange(0, WHERE_PREFIX.length) })
    } else {
        null
    }
}

private fun List<String>.calculateRange(index: Int): TextRange {
    val start = this.take(index).sumBy { it.length + 2 } // plus ", "
    return TextRange(start, start + this[index].length)
}
