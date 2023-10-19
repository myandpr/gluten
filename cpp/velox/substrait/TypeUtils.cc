/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "TypeUtils.h"
#include "SubstraitParser.h"
#include "velox/type/Type.h"

namespace gluten {
std::vector<std::string_view> getTypesFromCompoundName(std::string_view compoundName) {
  // CompoundName is like ARRAY<BIGINT> or MAP<BIGINT,DOUBLE>
  // or ROW<BIGINT,ROW<DOUBLE,BIGINT>,ROW<DOUBLE,BIGINT>>
  // the position of then delimiter is where the number of leftAngleBracket
  // equals rightAngleBracket need to split.
  std::vector<std::string_view> types;
  std::vector<int> angleBracketNumEqualPos;
  auto leftAngleBracketPos = compoundName.find("<");
  auto rightAngleBracketPos = compoundName.rfind(">");
  auto typesName = compoundName.substr(leftAngleBracketPos + 1, rightAngleBracketPos - leftAngleBracketPos - 1);
  int leftAngleBracketNum = 0;
  int rightAngleBracketNum = 0;
  for (auto index = 0; index < typesName.length(); index++) {
    if (typesName[index] == '<') {
      leftAngleBracketNum++;
    }
    if (typesName[index] == '>') {
      rightAngleBracketNum++;
    }
    if (typesName[index] == ',' && rightAngleBracketNum == leftAngleBracketNum) {
      angleBracketNumEqualPos.push_back(index);
    }
  }
  int startPos = 0;
  for (auto delimeterPos : angleBracketNumEqualPos) {
    types.emplace_back(typesName.substr(startPos, delimeterPos - startPos));
    startPos = delimeterPos + 1;
  }
  types.emplace_back(std::string_view(typesName.data() + startPos, typesName.length() - startPos));
  return types;
}

// TODO Refactor using Bison.
std::string_view getNameBeforeDelimiter(const std::string& compoundName, const std::string& delimiter) {
  std::size_t pos = compoundName.find(delimiter);
  if (pos == std::string::npos) {
    return compoundName;
  }
  return std::string_view(compoundName.data(), pos);
}

std::pair<int32_t, int32_t> getPrecisionAndScale(const std::string& typeName) {
  std::size_t start = typeName.find_first_of("<");
  std::size_t end = typeName.find_last_of(">");
  if (start == std::string::npos || end == std::string::npos) {
    throw std::runtime_error("Invalid decimal type.");
  }

  std::string decimalType = typeName.substr(start + 1, end - start - 1);
  std::size_t token_pos = decimalType.find_first_of(",");
  auto precision = stoi(decimalType.substr(0, token_pos));
  auto scale = stoi(decimalType.substr(token_pos + 1, decimalType.length() - 1));
  return std::make_pair(precision, scale);
}

TypePtr toVeloxType(const std::string& typeName, bool asLowerCase) {
  VELOX_CHECK(!typeName.empty(), "Cannot convert empty string to Velox type.");
  auto type = std::string(getNameBeforeDelimiter(typeName, "<"));
  if (DATE()->toString() == type) {
    return DATE();
  }
  if (type == "SHORT_DECIMAL") {
    auto decimal = getPrecisionAndScale(typeName);
    return DECIMAL(decimal.first, decimal.second);
  }
  auto typeKind = mapNameToTypeKind(type);
  switch (typeKind) {
    case TypeKind::BOOLEAN:
      return BOOLEAN();
    case TypeKind::TINYINT:
      return TINYINT();
    case TypeKind::SMALLINT:
      return SMALLINT();
    case TypeKind::INTEGER:
      return INTEGER();
    case TypeKind::BIGINT:
      return BIGINT();
    case TypeKind::HUGEINT: {
      auto decimal = getPrecisionAndScale(typeName);
      return DECIMAL(decimal.first, decimal.second);
    }
    case TypeKind::REAL:
      return REAL();
    case TypeKind::DOUBLE:
      return DOUBLE();
    case TypeKind::VARCHAR:
      return VARCHAR();
    case TypeKind::VARBINARY:
      return VARBINARY();
    case TypeKind::ARRAY: {
      auto fieldTypes = getTypesFromCompoundName(typeName);
      VELOX_CHECK_EQ(fieldTypes.size(), 1, "The size of ARRAY type should be only one.");
      return ARRAY(toVeloxType(std::string(fieldTypes[0]), asLowerCase));
    }
    case TypeKind::MAP: {
      auto fieldTypes = getTypesFromCompoundName(typeName);
      VELOX_CHECK_EQ(fieldTypes.size(), 2, "The size of MAP type should be two.");
      auto keyType = toVeloxType(std::string(fieldTypes[0]), asLowerCase);
      auto valueType = toVeloxType(std::string(fieldTypes[1]), asLowerCase);
      return MAP(keyType, valueType);
    }
    case TypeKind::ROW: {
      auto fieldTypes = getTypesFromCompoundName(typeName);
      VELOX_CHECK(!fieldTypes.empty(), "Converting empty ROW type from Substrait to Velox is not supported.");

      std::vector<TypePtr> types;
      std::vector<std::string> names;
      for (int idx = 0; idx < fieldTypes.size(); idx++) {
        std::string fieldTypeAndName = std::string(fieldTypes[idx]);
        size_t pos = fieldTypeAndName.find_last_of(':');
        if (pos == std::string::npos) {
          // Name does not exist.
          types.emplace_back(toVeloxType(fieldTypeAndName, asLowerCase));
          names.emplace_back("col_" + std::to_string(idx));
        } else {
          types.emplace_back(toVeloxType(fieldTypeAndName.substr(0, pos), asLowerCase));
          std::string fieldName = fieldTypeAndName.substr(pos + 1, fieldTypeAndName.length());
          if (asLowerCase) {
            folly::toLowerAscii(fieldName);
          }
          names.emplace_back(fieldName);
        }
      }
      return ROW(std::move(names), std::move(types));
    }
    case TypeKind::TIMESTAMP: {
      return TIMESTAMP();
    }
    case TypeKind::UNKNOWN:
      return UNKNOWN();
    default:
      VELOX_NYI("Velox type conversion not supported for type {}.", typeName);
  }
}

TypePtr substraitTypeToVeloxType(const std::string& substraitType) {
  return toVeloxType(SubstraitParser::parseType(substraitType));
}

TypePtr substraitTypeToVeloxType(const ::substrait::Type& substraitType) {
  return toVeloxType(SubstraitParser::parseType(substraitType).type);
}

TypePtr getRowType(const std::string& structType) {
  // Struct info is in the format of struct<T1,T2, ...,Tn>.
  // TODO: nested struct is not supported.
  auto structStart = structType.find_first_of('<');
  auto structEnd = structType.find_last_of('>');
  VELOX_CHECK(
      structEnd - structStart > 1, "native validation failed due to: More information is needed to create RowType");
  std::string childrenTypes = structType.substr(structStart + 1, structEnd - structStart - 1);

  // Split the types with delimiter.
  std::string delimiter = ",";
  std::size_t pos;
  std::vector<TypePtr> types;
  std::vector<std::string> names;
  while ((pos = childrenTypes.find(delimiter)) != std::string::npos) {
    const auto& typeStr = childrenTypes.substr(0, pos);
    std::string decDelimiter = ">";
    if (typeStr.find("dec") != std::string::npos) {
      std::size_t endPos = childrenTypes.find(decDelimiter);
      VELOX_CHECK(endPos >= pos + 1, "Decimal scale is expected.");
      const auto& decimalStr = typeStr + childrenTypes.substr(pos, endPos - pos) + decDelimiter;
      types.emplace_back(getDecimalType(decimalStr));
      names.emplace_back("");
      childrenTypes.erase(0, endPos + delimiter.length() + decDelimiter.length());
      continue;
    }

    types.emplace_back(substraitTypeToVeloxType(typeStr));
    names.emplace_back("");
    childrenTypes.erase(0, pos + delimiter.length());
  }
  types.emplace_back(substraitTypeToVeloxType(childrenTypes));
  names.emplace_back("");
  return std::make_shared<RowType>(std::move(names), std::move(types));
}

TypePtr getDecimalType(const std::string& decimalType) {
  // Decimal info is in the format of dec<precision,scale>.
  auto precisionStart = decimalType.find_first_of('<');
  auto tokenIndex = decimalType.find_first_of(',');
  auto scaleStart = decimalType.find_first_of('>');
  auto precision = stoi(decimalType.substr(precisionStart + 1, (tokenIndex - precisionStart - 1)));
  auto scale = stoi(decimalType.substr(tokenIndex + 1, (scaleStart - tokenIndex - 1)));
  return DECIMAL(precision, scale);
}

std::vector<TypePtr> sigToTypes(const std::string& functionSig) {
  std::vector<std::string> typeStrs;
  SubstraitParser::getSubFunctionTypes(functionSig, typeStrs);
  std::vector<TypePtr> types;
  types.reserve(typeStrs.size());
  for (const auto& typeStr : typeStrs) {
    if (typeStr.find("struct") != std::string::npos) {
      types.emplace_back(getRowType(typeStr));
    } else if (typeStr.find("dec") != std::string::npos) {
      types.emplace_back(getDecimalType(typeStr));
    } else {
      types.emplace_back(substraitTypeToVeloxType(typeStr));
    }
  }
  return types;
}

} // namespace gluten