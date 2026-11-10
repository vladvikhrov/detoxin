package com.detoxin.app.guard

/**
 * «Тест на трезвость»: фраза, которую нужно набрать вручную перед экстренной разблокировкой
 * или снятием ограничений. Проверяется точное совпадение символов (регистр, запятая, буквы).
 */
object SobrietyPhrase {

    const val TEXT = "Я осознаю, что трачу свое время впустую и нарушаю обещание, данное себе вчера"

    /** Пробелы в конце не считаются: клавиатуры добавляют их сами после слова. */
    fun matches(input: String): Boolean = input.trimEnd() == TEXT

    /**
     * Допустима ли правка поля: за одно изменение текст может вырасти не больше чем на один
     * символ. Вставка из буфера обмена, подстановка слова целиком и ввод свайпом добавляют
     * сразу несколько символов и поэтому отклоняются; удаление разрешено всегда.
     */
    fun acceptsEdit(previous: String, next: String): Boolean = next.length <= previous.length + 1

    /** Сколько символов с начала набрано верно — для подсказки прогресса. */
    fun correctPrefixLength(input: String): Int {
        var index = 0
        while (index < input.length && index < TEXT.length && input[index] == TEXT[index]) index++
        return index
    }
}
