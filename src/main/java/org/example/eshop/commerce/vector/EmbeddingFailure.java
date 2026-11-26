package org.example.eshop.commerce.vector;

/** Only fixed codes cross the provider boundary; response bodies and credentials never do. */
final class EmbeddingFailure extends RuntimeException {
    EmbeddingFailure(String code) { super(code); }
}
