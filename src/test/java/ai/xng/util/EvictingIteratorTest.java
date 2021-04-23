package ai.xng.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;

import com.google.common.collect.Iterators;

import org.junit.jupiter.api.Test;

import lombok.val;

public class EvictingIteratorTest {
  @Test
  public void testEvictLeading() {
    val list = new ArrayList<Integer>(Arrays.asList(8, 6, 7, 5, 3, 0, 9));
    assertThat(new EvictingIterator.Lambda<>(list.iterator(), i -> i % 2 == 0))
        .toIterable().containsExactly(7, 5, 3, 9);
    assertThat(list).containsExactly(7, 5, 3, 9);
  }

  @Test
  public void testEvictTrailing() {
    val list = new ArrayList<Integer>(Arrays.asList(8, 6, 7, 5, 3, 0, 9));
    assertThat(new EvictingIterator.Lambda<>(list.iterator(), i -> i % 2 == 1))
        .toIterable().containsExactly(8, 6, 0);
    assertThat(list).containsExactly(8, 6, 0);
  }

  @Test
  public void testRemoveLeading() {
    val list = new ArrayList<Integer>(Arrays.asList(8, 6, 7, 5, 3, 0, 9));
    Iterators.removeIf(new EvictingIterator.Lambda<>(list.iterator(), i -> i % 2 == 1),
        i -> i > 0);
    assertThat(list).containsExactly(0);
  }

  @Test
  public void testRemoveTrailing() {
    val list = new ArrayList<Integer>(Arrays.asList(8, 6, 7, 5, 3, 0, 9));
    Iterators.removeIf(new EvictingIterator.Lambda<>(list.iterator(), i -> i % 2 == 0),
        i -> i > 5);
    assertThat(list).containsExactly(5, 3);
  }

  @Test
  public void testNested() {
    val list = new ArrayList<Integer>(Arrays.asList(8, 6, 7, 5, 3, 0, 9));
    assertThat(new EvictingIterator.Lambda<>(new EvictingIterator.Lambda<>(list.iterator(),
        i -> i % 2 == 0),
        i -> i % 3 == 0)).toIterable().containsExactly(7, 5);
    assertThat(list).containsExactly(7, 5);
  }
}
