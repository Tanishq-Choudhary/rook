package dev.rook.codeintel.impact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NameFormsTest {

    @Test
    void splitsAcrossStyles() {
        assertThat(NameForms.words("customer_id")).containsExactly("customer", "id");
        assertThat(NameForms.words("customerId")).containsExactly("customer", "id");
        assertThat(NameForms.words("CustomerID")).containsExactly("customer", "id");
        assertThat(NameForms.words("REQUIRES_ACTION")).containsExactly("requires", "action");
    }

    @Test
    void convertsBetweenStyles() {
        assertThat(NameForms.camel("customer_id")).isEqualTo("customerId");
        assertThat(NameForms.pascal("customer_id")).isEqualTo("CustomerId");
        assertThat(NameForms.upperSnake("requiresAction")).isEqualTo("REQUIRES_ACTION");
        assertThat(NameForms.upperSnake("failed")).isEqualTo("FAILED");
    }

    @Test
    void sameWordsIgnoresStyle() {
        assertThat(NameForms.sameWords("customer_id", "customerId")).isTrue();
        assertThat(NameForms.sameWords("customer_id", "customer")).isFalse();
    }

    @Test
    void accessorNamesCoverGettersSettersRecordsAndBuilders() {
        assertThat(NameForms.accessorNames("customer_id"))
                .contains(
                        "getCustomerId",
                        "isCustomerId",
                        "setCustomerId",
                        "customerId",
                        "withCustomerId");
    }
}
