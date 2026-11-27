package com.banking.yankiservice.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.yankiservice.application.model.ResultPage;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageRequestTest {

  @Test
  void missingValuesTakeTheDefaults() {
    assertThat(PageRequest.of(null, null)).isEqualTo(new PageRequest(0, 20));
    assertThat(PageRequest.of(3, 10).offset()).isEqualTo(30);
  }

  @Test
  void aPageOrASizeOutOfRangeIsRejected() {
    assertThatThrownBy(() -> new PageRequest(-1, 20)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PageRequest(10_001, 20))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PageRequest(0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PageRequest(0, 101)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void totalPagesIsTheCeilingAndZeroWhenThereIsNothing() {
    assertThat(ResultPage.of(List.of("a"), new PageRequest(0, 2), 5).totalPages()).isEqualTo(3);
    assertThat(ResultPage.of(List.of(), new PageRequest(0, 2), 0).totalPages()).isZero();
  }
}
