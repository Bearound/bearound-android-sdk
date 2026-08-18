package io.bearound.sdk.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Medido em campo: aparelhos que perdiam a primeira leitura do advertising ID ficavam sem ele
 * em TODAS as sessões seguintes, enquanto os que acertavam de primeira reportavam em 100%
 * delas. O que separa os dois casos é esta política — por isso ela é testada, e não o
 * `AdvertisingIdClient`, que nem existe em ambiente de teste.
 */
class AdvertisingIdCollectorTest {

    private val UMA_HORA = 60 * 60 * 1000L
    private val SEIS_HORAS = 6 * UMA_HORA

    @Test
    fun `sem valor e sem falha registrada, busca de imediato`() {
        assertTrue(
            AdvertisingIdCollector.deveBuscar(
                temValor = false, agoraMs = 0, ultimoSucessoMs = 0, proximaTentativaMs = 0,
            )
        )
    }

    @Test
    fun `sem valor, respeita o backoff da falha anterior`() {
        val agora = 10_000L
        assertFalse(
            "antes do prazo não insiste",
            AdvertisingIdCollector.deveBuscar(
                temValor = false, agoraMs = agora, ultimoSucessoMs = 0,
                proximaTentativaMs = agora + 1,
            )
        )
        assertTrue(
            "vencido o prazo, tenta de novo — é isso que impede o null permanente",
            AdvertisingIdCollector.deveBuscar(
                temValor = false, agoraMs = agora, ultimoSucessoMs = 0,
                proximaTentativaMs = agora,
            )
        )
    }

    @Test
    fun `com valor fresco, nao relê`() {
        assertFalse(
            AdvertisingIdCollector.deveBuscar(
                temValor = true, agoraMs = SEIS_HORAS - 1, ultimoSucessoMs = 0,
                proximaTentativaMs = 0,
            )
        )
    }

    @Test
    fun `com valor vencido, relê — o usuário pode ter resetado o id`() {
        assertTrue(
            AdvertisingIdCollector.deveBuscar(
                temValor = true, agoraMs = SEIS_HORAS, ultimoSucessoMs = 0,
                proximaTentativaMs = 0,
            )
        )
    }

    @Test
    fun `backoff dobra a cada falha e para no teto`() {
        assertEquals(30_000L, AdvertisingIdCollector.esperaDoBackoff(1))
        assertEquals(60_000L, AdvertisingIdCollector.esperaDoBackoff(2))
        assertEquals(120_000L, AdvertisingIdCollector.esperaDoBackoff(3))
        val teto = 30 * 60 * 1000L
        assertEquals(teto, AdvertisingIdCollector.esperaDoBackoff(7))
        assertEquals("nunca ultrapassa o teto", teto, AdvertisingIdCollector.esperaDoBackoff(99))
    }

    @Test
    fun `backoff não estoura com contagem inesperada`() {
        assertEquals(30_000L, AdvertisingIdCollector.esperaDoBackoff(0))
    }
}
