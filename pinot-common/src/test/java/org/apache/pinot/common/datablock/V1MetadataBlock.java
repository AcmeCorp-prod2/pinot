/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.common.datablock;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;


/**
 * The datablock used before the introduction of {@link org.apache.pinot.common.datatable.StatMap}.
 * <p>
 * This version stored the metadata in a {@code Map<String, String>} which was encoded as JSON and stored in the
 * variable size data buffer.
 * <p>
 * Instances of this class are not actually seen by the operators. Instead, they are converted to {@link MetadataBlock}
 * in {@link MetadataBlock#deserialize(ByteBuffer, int)}.
 * <p>
 * The reason to keep it here is mostly for backwards compatibility and testing. In order to simplify the code, the
 * stats engine just ignores the metadata of these objects, but we need to be able to deserialize them anyway.
 */
public class V1MetadataBlock extends BaseDataBlock {

  private static final ObjectMapper JSON = new ObjectMapper();

  @VisibleForTesting
  static final int VERSION = 1;

  /**
   * Used to serialize the contents of the metadata block conveniently and in
   * a backwards compatible way. Use JSON because the performance of metadata block
   * SerDe should not be a bottleneck.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @VisibleForTesting
  static class Contents {

    private String _type;
    private Map<String, String> _stats;

    @JsonCreator
    public Contents(@JsonProperty("type") String type, @JsonProperty("stats") Map<String, String> stats) {
      _type = type;
      _stats = stats;
    }

    @JsonCreator
    public Contents() {
      this(null, new HashMap<>());
    }

    public String getType() {
      return _type;
    }

    public void setType(String type) {
      _type = type;
    }

    public Map<String, String> getStats() {
      return _stats;
    }

    public void setStats(Map<String, String> stats) {
      _stats = stats;
    }
  }

  private final Contents _contents;

  public V1MetadataBlock(MetadataBlock.MetadataBlockType type) {
    this(type, new HashMap<>());
  }

  public V1MetadataBlock(MetadataBlock.MetadataBlockType type, Map<String, String> stats) {
    super(0, null, new String[0], new byte[]{0}, toContents(new Contents(type.name(), stats)));
    _contents = new Contents(type.name(), stats);
  }

  private static byte[] toContents(Contents type) {
    try {
      return JSON.writeValueAsBytes(type);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  public V1MetadataBlock(ByteBuffer byteBuffer)
      throws IOException {
    super(byteBuffer);
    if (_variableSizeDataBytes != null && _variableSizeDataBytes.length > 0) {
      _contents = JSON.readValue(_variableSizeDataBytes, Contents.class);
    } else {
      _contents = new Contents();
    }
  }

  public MetadataBlock.MetadataBlockType getType() {
    String type = _contents.getType();

    // if type is null, then we're reading a legacy block where we didn't encode any
    // data. assume that it is an EOS block if there's no exceptions and an ERROR block
    // otherwise
    return type == null
        ? (getExceptions().isEmpty() ? MetadataBlock.MetadataBlockType.EOS : MetadataBlock.MetadataBlockType.ERROR)
        : MetadataBlock.MetadataBlockType.valueOf(type);
  }

  public Map<String, String> getStats() {
    return _contents.getStats() != null ? _contents.getStats() : new HashMap<>();
  }

  @Override
  public int getDataBlockVersionType() {
    return VERSION + (Type.METADATA.ordinal() << DataBlockUtils.VERSION_TYPE_SHIFT);
  }

  @Override
  protected int getOffsetInFixedBuffer(int rowId, int colId) {
    throw new UnsupportedOperationException("Metadata block uses JSON encoding for field access");
  }

  @Override
  protected int positionOffsetInVariableBufferAndGetLength(int rowId, int colId) {
    throw new UnsupportedOperationException("Metadata block uses JSON encoding for field access");
  }
}
