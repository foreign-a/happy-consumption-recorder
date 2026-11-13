package com.happy.consumption.data
import org.junit.Assert.*
import org.junit.Test

class DailyStatisticsTest {
    private val item=ConsumptionItem(1,1,"2026-10-21",100,200,300,400,true,0)
    @Test fun fourSharesUseTheSameCurrencyTotal() {
        val shares=dailyShares(item,MoneyUnit.YUAN)
        assertEquals(listOf(100L,200L,300L,400L),shares.map { it.amount })
        assertEquals(1f,shares.sumOf { it.fraction.toDouble() }.toFloat(),0.00001f)
        assertEquals(0.4f,shares.last().fraction,0.00001f)
    }
    @Test fun mixedCurrenciesNeverShareADenominator() {
        val mixed=item.copy(otherCurrency=MoneyUnit.DOLLAR)
        assertEquals(0f,dailyShares(mixed,MoneyUnit.YUAN).last().fraction,0f)
        assertEquals(1f,dailyShares(mixed,MoneyUnit.DOLLAR).last().fraction,0f)
        assertEquals(0.5f,dailyShares(mixed,MoneyUnit.YUAN)[2].fraction,0.00001f)
    }
    @Test fun zeroAndMaximumAmountsRemainFinite() {
        assertTrue(dailyShares(item.copy(breakfast=0,lunch=0,dinner=0,other=0),MoneyUnit.YUAN).all { it.fraction==0f })
        assertTrue(dailyShares(item.copy(breakfast=Long.MAX_VALUE,lunch=Long.MAX_VALUE,dinner=Long.MAX_VALUE,other=Long.MAX_VALUE),MoneyUnit.YUAN).all { it.fraction==0.25f })
    }
    @Test fun hexColorsAreNormalizedAndInvalidValuesRejected() {
        assertEquals("#AABBCC",ChartColors.normalize(" aabbcc "))
        assertEquals("#000000",ChartColors.normalize("#000000"))
        for (color in listOf("", "#abc", "#00000000", "#GGGGGG", "red")) assertNull(ChartColors.normalize(color))
    }
}
