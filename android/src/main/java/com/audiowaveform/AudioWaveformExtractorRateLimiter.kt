package com.audiowaveform

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.Delegates

class AudioWaveformExtractorRateLimiter() {
    constructor(nbOfParallelAllowedExtraction: Int = 3) : this() {
        this.nbOfParallelAllowedExtraction = nbOfParallelAllowedExtraction
        this.nbOfAllowedExtraction = AtomicInteger(nbOfParallelAllowedExtraction)
    }

    private var nbOfParallelAllowedExtraction by Delegates.notNull<Int>()
    private lateinit var nbOfAllowedExtraction: AtomicInteger
    private val extractionQueue: ConcurrentLinkedQueue<WaveformExtractor> = ConcurrentLinkedQueue()

    fun add(waveformExtractor: WaveformExtractor, previousExtractor: WaveformExtractor?) {
        extractionQueue.add(waveformExtractor)
        previousExtractor.let { extractionQueue.remove(it) }
        processQueue()
    }

    fun continueQueue() {
        nbOfAllowedExtraction.incrementAndGet()
        processQueue()
    }

    @Synchronized
    private fun getNext(): WaveformExtractor? {
        return if (extractionQueue.isEmpty() || nbOfAllowedExtraction.get() <= 0) null
        else extractionQueue.poll()?.also { nbOfAllowedExtraction.decrementAndGet() }
    }

    private fun processQueue() {
        getNext()?.startDecode()
    }

    fun reset() {
        extractionQueue.onEach { extractor -> extractor.stop() }
        extractionQueue.clear()
        this.nbOfAllowedExtraction.set(this.nbOfParallelAllowedExtraction)
    }
}