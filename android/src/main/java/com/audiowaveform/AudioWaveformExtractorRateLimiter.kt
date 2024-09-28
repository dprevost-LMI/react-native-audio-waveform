package com.audiowaveform

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class AudioWaveformExtractorRateLimiter() {
    constructor(nbOfParallelAllowedExtraction: Int = 3) : this() {
        this.nbOfParallelAllowedExtraction = AtomicInteger(nbOfParallelAllowedExtraction)
    }

    private lateinit var nbOfParallelAllowedExtraction: AtomicInteger
    private val extractionQueue: ConcurrentLinkedQueue<WaveformExtractor> = ConcurrentLinkedQueue()

    fun add(waveformExtractor: WaveformExtractor, previousExtractor: WaveformExtractor?) {
        extractionQueue.add(waveformExtractor)
        previousExtractor.let { extractionQueue.remove(it) }
        processQueue()
    }

    fun continueQueue() {
        nbOfParallelAllowedExtraction.incrementAndGet()
        processQueue()
    }

    @Synchronized
    private fun getNext(): WaveformExtractor? {
        return if (extractionQueue.isEmpty() || nbOfParallelAllowedExtraction.get() <= 0) null
        else extractionQueue.poll()?.also { nbOfParallelAllowedExtraction.decrementAndGet() }
    }

    private fun processQueue() {
        getNext()?.startDecode()
    }
}