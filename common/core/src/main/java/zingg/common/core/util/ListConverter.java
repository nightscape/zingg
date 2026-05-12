package zingg.common.core.util;

import scala.jdk.javaapi.CollectionConverters;
import scala.collection.immutable.Seq;

import java.util.List;

public class ListConverter<C> {
    public Seq<C> convertListToSeq(List<C> inputList) {
        return CollectionConverters.asScala(inputList).toSeq();
    }
}