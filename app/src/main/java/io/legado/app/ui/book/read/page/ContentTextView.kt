package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.PhotoDialog
import io.legado.app.ui.book.read.page.entities.TextChar
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.ImageProvider
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.utils.*
import kotlin.math.min

/**
 * 阅读内容界面
 */
class ContentTextView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var selectAble = context.getPrefBoolean(PreferKey.textSelectAble, true)
    var upView: ((TextPage) -> Unit)? = null
    private val selectedPaint by lazy {
        Paint().apply {
            color = context.getCompatColor(R.color.btn_bg_press_2)
            style = Paint.Style.FILL
        }
    }
    private var callBack: CallBack
    private val visibleRect = RectF()
    private val selectStart = Pos(0, 0, 0)
    private val selectEnd = Pos(0, 0, 0)
    var textPage: TextPage = TextPage()
        private set

    //滚动参数
    private val pageFactory: TextPageFactory get() = callBack.pageFactory
    private var pageOffset = 0

    init {
        callBack = activity as CallBack
    }

    fun setContent(textPage: TextPage) {
        this.textPage = textPage
        invalidate()
    }

    fun upVisibleRect() {
        visibleRect.set(
            ChapterProvider.paddingLeft.toFloat(),
            ChapterProvider.paddingTop.toFloat(),
            ChapterProvider.visibleRight.toFloat(),
            ChapterProvider.visibleBottom.toFloat()
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ChapterProvider.upViewSize(w, h)
        upVisibleRect()
        textPage.format()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.clipRect(visibleRect)
        drawPage(canvas)
    }

    /**
     * 绘制页面
     */
    private fun drawPage(canvas: Canvas) {
        var relativeOffset = relativeOffset(0)
        textPage.textLines.forEach { textLine ->
            draw(canvas, textPage, textLine, relativeOffset)
        }
        if (!callBack.isScroll) return
        //滚动翻页
        if (!pageFactory.hasNext()) return
        val textPage1 = relativePage(1)
        relativeOffset = relativeOffset(1)
        textPage1.textLines.forEach { textLine ->
            draw(canvas, textPage1, textLine, relativeOffset)
        }
        if (!pageFactory.hasNextPlus()) return
        relativeOffset = relativeOffset(2)
        if (relativeOffset < ChapterProvider.visibleHeight) {
            val textPage2 = relativePage(2)
            textPage2.textLines.forEach { textLine ->
                draw(canvas, textPage2, textLine, relativeOffset)
            }
        }
    }

    private fun draw(
        canvas: Canvas,
        textPage: TextPage,
        textLine: TextLine,
        relativeOffset: Float,
    ) {
        val lineTop = textLine.lineTop + relativeOffset
        val lineBase = textLine.lineBase + relativeOffset
        val lineBottom = textLine.lineBottom + relativeOffset
        drawChars(canvas, textPage, textLine, lineTop, lineBase, lineBottom)
    }

    /**
     * 绘制文字
     */
    private fun drawChars(
        canvas: Canvas,
        textPage: TextPage,
        textLine: TextLine,
        lineTop: Float,
        lineBase: Float,
        lineBottom: Float,
    ) {
        val textPaint = if (textLine.isTitle) {
            ChapterProvider.titlePaint
        } else {
            ChapterProvider.contentPaint
        }
        val textColor = if (textLine.isReadAloud) context.accentColor else ReadBookConfig.textColor
        textLine.textChars.forEach {
            if (it.isImage) {
                drawImage(canvas, textPage, textLine, it, lineTop, lineBottom)
            } else {
                textPaint.color = textColor
                if (it.isSearchResult) {
                    textPaint.color = context.accentColor
                }
                canvas.drawText(it.charData, it.start, lineBase, textPaint)
            }
            if (it.selected) {
                canvas.drawRect(it.start, lineTop, it.end, lineBottom, selectedPaint)
            }
        }
    }

    /**
     * 绘制图片
     */
    @Suppress("UNUSED_PARAMETER")
    private fun drawImage(
        canvas: Canvas,
        textPage: TextPage,
        textLine: TextLine,
        textChar: TextChar,
        lineTop: Float,
        lineBottom: Float
    ) {
        val book = ReadBook.book ?: return
        val bitmap = ImageProvider.getImage(
            book,
            textChar.charData,
            (textChar.end - textChar.start).toInt(),
            (lineBottom - lineTop).toInt()
        )
        val rectF = if (textLine.isImage) {
            RectF(textChar.start, lineTop, textChar.end, lineBottom)
        } else {
            /*以宽度为基准保持图片的原始比例叠加，当div为负数时，允许高度比字符更高*/
            val h = (textChar.end - textChar.start) / bitmap.width * bitmap.height
            val div = (lineBottom - lineTop - h) / 2
            RectF(textChar.start, lineTop + div, textChar.end, lineBottom - div)
        }
        kotlin.runCatching {
            canvas.drawBitmap(bitmap, null, rectF, null)
        }.onFailure { e ->
            context.toastOnUi(e.localizedMessage)
        }
    }

    /**
     * 滚动事件
     */
    fun scroll(mOffset: Int) {
        if (mOffset == 0) return
        pageOffset += mOffset
        if (!pageFactory.hasPrev() && pageOffset > 0) {
            pageOffset = 0
        } else if (!pageFactory.hasNext()
            && pageOffset < 0
            && pageOffset + textPage.height < ChapterProvider.visibleHeight
        ) {
            val offset = (ChapterProvider.visibleHeight - textPage.height).toInt()
            pageOffset = min(0, offset)
        } else if (pageOffset > 0) {
            pageFactory.moveToPrev(false)
            textPage = pageFactory.curPage
            pageOffset -= textPage.height.toInt()
            upView?.invoke(textPage)
            contentDescription = textPage.text
        } else if (pageOffset < -textPage.height) {
            pageOffset += textPage.height.toInt()
            pageFactory.moveToNext(false)
            textPage = pageFactory.curPage
            upView?.invoke(textPage)
            contentDescription = textPage.text
        }
        invalidate()
    }

    fun resetPageOffset() {
        pageOffset = 0
    }

    /**
     * 选择文字
     */
    fun selectText(
        x: Float,
        y: Float,
        select: (relativePage: Int, lineIndex: Int, charIndex: Int) -> Unit,
    ) {
        if (!selectAble) return
        touch(x, y) { relativePos, textPage, _, lineIndex, _, charIndex, textChar ->
            if (textChar.isImage) {
                activity?.showDialogFragment(PhotoDialog(textPage.chapterIndex, textChar.charData))
            } else {
                textChar.selected = true
                invalidate()
                select(relativePos, lineIndex, charIndex)
            }
        }
    }

    /**
     * 开始选择符移动
     */
    fun selectStartMove(x: Float, y: Float) {
        touch(x, y) { relativePos, _, relativeOffset, lineIndex, textLine, charIndex, textChar ->
            val pos = Pos(relativePos, lineIndex, charIndex)
            if (selectStart.compare(pos) != 0) {
                if (pos.compare(selectEnd) <= 0) {
                    selectStart.upData(pos = pos)
                    upSelectedStart(
                        textChar.start,
                        textLine.lineBottom + relativeOffset,
                        textLine.lineTop + relativeOffset
                    )
                    upSelectChars()
                }
            }
        }
    }

    /**
     * 结束选择符移动
     */
    fun selectEndMove(x: Float, y: Float) {
        touch(x, y) { relativePos, _, relativeOffset, lineIndex, textLine, charIndex, textChar ->
            val pos = Pos(relativePos, lineIndex, charIndex)
            if (pos.compare(selectEnd) != 0) {
                if (pos.compare(selectStart) >= 0) {
                    selectEnd.upData(pos)
                    upSelectedEnd(textChar.end, textLine.lineBottom + relativeOffset)
                    upSelectChars()
                }
            }
        }
    }

    private fun touch(
        x: Float,
        y: Float,
        touched: (
            relativePos: Int,
            textPage: TextPage,
            relativeOffset: Float,
            lineIndex: Int,
            textLine: TextLine,
            charIndex: Int,
            textChar: TextChar
        ) -> Unit
    ) {
        if (!visibleRect.contains(x, y)) return
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            val textPage = relativePage(relativePos)
            for ((lineIndex, textLine) in textPage.textLines.withIndex()) {
                if (textLine.isTouch(x, y, relativeOffset)) {
                    for ((charIndex, textChar) in textLine.textChars.withIndex()) {
                        if (textChar.isTouch(x)) {
                            touched.invoke(
                                relativePos, textPage,
                                relativeOffset,
                                lineIndex, textLine,
                                charIndex, textChar
                            )
                            return
                        }
                    }
                    return
                }
            }
        }
    }

    /**
     * 选择开始文字
     */
    fun selectStartMoveIndex(relativePage: Int, lineIndex: Int, charIndex: Int) {
        selectStart.relativePos = relativePage
        selectStart.lineIndex = lineIndex
        selectStart.charIndex = charIndex
        val textLine = relativePage(relativePage).getLine(lineIndex)
        val textChar = textLine.getTextChar(charIndex)
        upSelectedStart(
            textChar.start,
            textLine.lineBottom + relativeOffset(relativePage),
            textLine.lineTop + relativeOffset(relativePage)
        )
        upSelectChars()
    }

    /**
     * 选择结束文字
     */
    fun selectEndMoveIndex(relativePage: Int, lineIndex: Int, charIndex: Int) {
        selectEnd.relativePos = relativePage
        selectEnd.lineIndex = lineIndex
        selectEnd.charIndex = charIndex
        val textLine = relativePage(relativePage).getLine(lineIndex)
        val textChar = textLine.getTextChar(charIndex)
        upSelectedEnd(textChar.end, textLine.lineBottom + relativeOffset(relativePage))
        upSelectChars()
    }

    private fun upSelectChars() {
        val last = if (callBack.isScroll) 2 else 0
        val charPos = Pos(0, 0, 0)
        for (relativePos in 0..last) {
            charPos.relativePos = relativePos
            for ((lineIndex, textLine) in relativePage(relativePos).textLines.withIndex()) {
                charPos.lineIndex = lineIndex
                for ((charIndex, textChar) in textLine.textChars.withIndex()) {
                    charPos.charIndex = charIndex
                    textChar.selected =
                        charPos.compare(selectStart) >= 0 && charPos.compare(selectEnd) <= 0
                    textChar.isSearchResult = textChar.selected && callBack.isSelectingSearchResult
                }
            }
        }
        invalidate()
    }

    private fun upSelectedStart(x: Float, y: Float, top: Float) = callBack.apply {
        upSelectedStart(x, y + headerHeight, top + headerHeight)
    }

    private fun upSelectedEnd(x: Float, y: Float) = callBack.apply {
        upSelectedEnd(x, y + headerHeight)
    }

    fun cancelSelect() {
        val last = if (callBack.isScroll) 2 else 0
        for (relativePos in 0..last) {
            relativePage(relativePos).textLines.forEach { textLine ->
                textLine.textChars.forEach {
                    it.selected = false
                }
            }
        }
        invalidate()
        callBack.onCancelSelect()
    }

    fun getSelectedText(): String {
        val pos = Pos(0, 0, 0)
        val builder = StringBuilder()
        for (relativePos in selectStart.relativePos..selectEnd.relativePos) {
            val textPage = relativePage(relativePos)
            pos.relativePos = relativePos
            textPage.textLines.forEachIndexed { lineIndex, textLine ->
                pos.lineIndex = lineIndex
                textLine.textChars.forEachIndexed { charIndex, textChar ->
                    pos.charIndex = charIndex
                    if (pos.compare(selectStart) >= 0
                        && pos.compare(selectEnd) <= 0
                    ) {
                        builder.append(textChar.charData)
                        if (charIndex == textLine.charSize - 1
                            && textLine.text.endsWith("\n")
                        ) {
                            builder.append("\n")
                        }
                    }
                }
            }
        }
        if (builder.endsWith("\n")) {
            return builder.substring(0, builder.lastIndex)
        }
        return builder.toString()
    }

    fun createBookmark(): Bookmark? {
        val page = relativePage(selectStart.relativePos)
        page.getTextChapter()?.let { chapter ->
            ReadBook.book?.let { book ->
                return book.createBookMark().apply {
                    chapterIndex = page.chapterIndex
                    chapterPos = chapter.getReadLength(page.index) +
                            page.getSelectStartLength(selectStart.lineIndex, selectStart.charIndex)
                    chapterName = chapter.title
                    bookText = getSelectedText()
                }
            }
        }
        return null
    }

    private fun relativeOffset(relativePos: Int): Float {
        return when (relativePos) {
            0 -> pageOffset.toFloat()
            1 -> pageOffset + textPage.height
            else -> pageOffset + textPage.height + pageFactory.nextPage.height
        }
    }

    fun relativePage(relativePos: Int): TextPage {
        return when (relativePos) {
            0 -> textPage
            1 -> pageFactory.nextPage
            else -> pageFactory.nextPlusPage
        }
    }

    private data class Pos(
        var relativePos: Int,
        var lineIndex: Int,
        var charIndex: Int
    ) {

        fun upData(pos: Pos) {
            relativePos = pos.relativePos
            lineIndex = pos.lineIndex
            charIndex = pos.charIndex
        }

        fun compare(pos: Pos): Int {
            if (relativePos < pos.relativePos) return -1
            if (relativePos > pos.relativePos) return 1
            if (lineIndex < pos.lineIndex) return -1
            if (lineIndex > pos.lineIndex) return 1
            if (charIndex < pos.charIndex) return -1
            if (charIndex > pos.charIndex) return 1
            return 0
        }
    }

    interface CallBack {
        fun upSelectedStart(x: Float, y: Float, top: Float)
        fun upSelectedEnd(x: Float, y: Float)
        fun onCancelSelect()
        val headerHeight: Int
        val pageFactory: TextPageFactory
        val isScroll: Boolean
        var isSelectingSearchResult: Boolean
    }
}
