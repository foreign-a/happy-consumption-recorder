package com.happy.consumption.data

object DatasetFixture {
    val rows by lazy {
        checkNotNull(javaClass.getResourceAsStream("/dataset.tsv")).bufferedReader().use { reader ->
            reader.readLines().map { it.split('\t') }
        }
    }
}
