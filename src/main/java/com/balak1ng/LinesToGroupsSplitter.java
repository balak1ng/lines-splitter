package com.balak1ng;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class LinesToGroupsSplitter {
    private static final Logger logger = Logger.getLogger(LinesToGroupsSplitter.class.toString());
    private static final String LINE_SEP = System.lineSeparator();
    private static final String OUTPUT = "result.txt";

    /**
     * Максимальное количество колонок в валидной строке.
     * Необходима для вычисления уникального индекса в методе findGroupsForEachString.
     */
    private static int maxColumns;

    /**
     * Хранилище всех уникальных валидных строк из файла в формате слов.
     */
    private static List<List<String>> uniqueValidRows;

    public static void main(String[] args) {
        long start = System.currentTimeMillis();

        uniqueValidRows = new ArrayList<>();
        maxColumns = 0;

        try {
            readFile(args[0]);
        } catch (Exception exception) {
            logger.info("Error while reading file.");
            return;
        }

        // Среди всех валидных строк находим повторяющиеся слова и фиксируем их.
        Map<String, Set<Integer>> duplicatedWordsAndTheirPositions = findDuplicatedWords();

        // Если слово повторяется - сохраним его позицию. Если позиция повторяется - формируем
        // новое условие для объединения строк между собой.
        Map<String, Set<Integer>> conditions = formConditions(duplicatedWordsAndTheirPositions);

        // Каждой строке сопоставим номера групп, которым она соответсвует.
        Map<Integer, List<Integer>> mapStringToGroups = findGroupsForEachString(conditions);

        // Создадим обратный маппинг - каждой группе сопоставим строки, которые в ней лежат.
        Map<Integer, Set<Integer>> groups = createGroups(mapStringToGroups);

        // На основании данных о группах и строках - объединим пересекающиеся группы между собой.
        mergeGroups(groups, mapStringToGroups);

        try {
            writeInfoToFile(groups, mapStringToGroups, start);
        } catch (IOException exception) {
            logger.info("Error while writing to file.");
        }
    }

    /**
     * Метод ведёт подсчёт всех повторяющихся слов в предложениях (на любых позициях) и сохраняет их в outputMap.
     */
    private static Map<String, Set<Integer>> findDuplicatedWords() {
        Map<String, Set<Integer>> outputMap = new HashMap<>();
        final Map<String, Integer> validWordFrequency = new HashMap<>();
        for (List<String> words : uniqueValidRows) {
            maxColumns = Math.max(maxColumns, words.size());
            for (String word : words) {
                if (!word.isEmpty()) {
                    validWordFrequency.put(word, validWordFrequency.getOrDefault(word, 0) + 1);
                    if (validWordFrequency.get(word) > 1) {
                        outputMap.put(word, new HashSet<>());
                    }
                }
            }
        }
        return outputMap;
    }

    /**
     * Перевод количества байт памяти в легко-читаемый формат отображения.
     */
    public static String formatSize(long v) {
        if (v < 1024) return v + " B";
        int z = (63 - Long.numberOfLeadingZeros(v)) / 10;
        return String.format("%.1f %sB", (double) v / (1L << (z * 10)), " KMGTPE".charAt(z));
    }

    /**
     * Проверить валидность слова, корректный формат: "12345" = "^\d+$".
     */
    private static boolean isWordValid(String str) {
        for (char letter : str.toCharArray()) {
            if (letter != '.' && (letter < '0' || letter > '9')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Проверить валидность строки, т.е. каждое слово валидно.
     */
    private static boolean isRowValid(String row) {
        String[] words = row.split(";");
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            String temp = word.substring(1, word.length() - 1);
            if (!isWordValid(temp)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Метод читает все строки из файла и отбирает из них валидные и уникальные.
     * Каждая валидная строка целиком (разбитая на слова) добавляется в uniqueValidRows.
     */
    private static void readFile(String path) throws IOException {
        String nextLine;

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            final Set<String> uniqueRows = new HashSet<>();

            while ((nextLine = reader.readLine()) != null) {
                if (!isRowValid(nextLine) || !uniqueRows.add(nextLine)) {
                    continue;
                }

                uniqueValidRows.add(new ArrayList<>());

                String[] words = nextLine.split(";");
                for (String word : words) {
                    if (word.isEmpty()) {
                        uniqueValidRows.get(uniqueValidRows.size() - 1).add(word);
                    } else {
                        String temp = word.substring(1, word.length() - 1);
                        uniqueValidRows.get(uniqueValidRows.size() - 1).add(temp);
                    }
                }
            }
        }
    }

    /**
     * Если слово, многократно встречающееся в файле, также несколько раз встречается на определенной позиции
     * в разных строках - это становится условием для создания новой группы (для объединения строк).
     * Под условием объединения подразумевается слово на определенной позиции.
     */
    private static Map<String, Set<Integer>> formConditions(Map<String, Set<Integer>> duplicatedWordsAndTheirPositions) {
        Map<String, Set<Integer>> conditions = new HashMap<>();

        for (List<String> row : uniqueValidRows) {
            for (int i = 0; i < row.size(); i++) {
                String word = row.get(i);
                if (duplicatedWordsAndTheirPositions.containsKey(word)
                        && (!duplicatedWordsAndTheirPositions.get(word).add(i))) {
                    conditions.computeIfAbsent(word, x -> new HashSet<>()).add(i);
                }
            }
        }

        return conditions;
    }


    /**
     * На основании вычисленных условий сопоставим каждой строке список подходящих ей групп.
     * Каждому слову сопоставим уникальный идентификатор.
     * При этом какое-то слово может являться объединяющим ключём для разных пар строк в разных столбцах,
     * то есть идентификатор теряет уникальность - придумаем своё правило вычисления generatedUniqueGroupId.
     * generatedUniqueGroupId = номер условия * (maxColumns + 1) + позиция слова в столбце.
     * Таким образом пересечений не будет (подобие кеша, матрицы).
     */
    private static Map<Integer, List<Integer>> findGroupsForEachString(Map<String, Set<Integer>> conditions) {
        Map<String, Integer> uniqueStringConditionIndex = new HashMap<>();

        int k = 0;
        for (Map.Entry<String, Set<Integer>> entry : conditions.entrySet()) {
            uniqueStringConditionIndex.put(entry.getKey(), k);
            k++;
        }

        Map<Integer, List<Integer>> mapStringToGroupIds = new HashMap<>();

        for (int j = 0; j < uniqueValidRows.size(); j++) {
            for (int i = 0; i < uniqueValidRows.get(j).size(); i++) {
                String word = uniqueValidRows.get(j).get(i);
                if (conditions.containsKey(word) && conditions.get(word).contains(i)) {
                    int generatedUniqueGroupId = uniqueStringConditionIndex.get(word) * (maxColumns + 1) + i;
                    mapStringToGroupIds.computeIfAbsent(j, x -> new ArrayList<>()).add(generatedUniqueGroupId);
                }
            }
        }

        return mapStringToGroupIds;
    }

    /**
     * Если строка принадлежит сразу нескольким группам - эти группы нужно объединить.
     * Если одна группа поглотила вторую - выставим ей родителя в parentGroup и удалим её из groups.
     * Таким образом решаем проблему ассоциативности.
     */
    private static void mergeGroups(Map<Integer, Set<Integer>> groups, Map<Integer, List<Integer>> mapStringIdToGroupIds) {
        Map<Integer, Integer> parentGroup = new HashMap<>();

        for (Map.Entry<Integer, List<Integer>> entry : mapStringIdToGroupIds.entrySet()) {
            for (int i = 1; i < entry.getValue().size(); i++) {
                int firstParent = findParent(parentGroup, entry.getValue().get(i));
                int secondParent = findParent(parentGroup, entry.getValue().get(i - 1));

                if (firstParent == secondParent) {
                    continue;
                }

                groups.get(firstParent).addAll(groups.get(secondParent));
                groups.remove(secondParent);
                parentGroup.put(secondParent, firstParent);
            }
        }
    }

    private static int findParent(Map<Integer, Integer> parentGroup, int x) {
        if (!parentGroup.containsKey(x)) {
            return x;
        }
        return findParent(parentGroup, parentGroup.get(x));
    }


    /**
     * Теперь делаем обратный маппинг - номеру группы сопоставляем список строк.
     */
    private static Map<Integer, Set<Integer>> createGroups(Map<Integer, List<Integer>> mapStringToGroups) {
        Map<Integer, Set<Integer>> groups = new HashMap<>();

        for (Map.Entry<Integer, List<Integer>> entry : mapStringToGroups.entrySet()) {
            for (Integer groupId : entry.getValue()) {
                groups.computeIfAbsent(groupId, x -> new HashSet<>()).add(entry.getKey());
            }
        }

        return groups;
    }

    /**
     * Запишем в файл все сформированные группы строк и дополнительную информацию о работе приложения.
     */
    private static void writeInfoToFile(Map<Integer, Set<Integer>> groups,
                                        Map<Integer, List<Integer>> mapStringToGroups,
                                        long start) throws IOException {

        int groupId = 1;

        // Сортируем группы по количеству строк.
        groups = groups.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT))) {
            writer.write("There are " + groups.size() + " groups with 2 elements and more." + LINE_SEP + LINE_SEP);

            for (Map.Entry<Integer, Set<Integer>> entry : groups.entrySet()) {

                writer.write("Group #" + groupId + " (consists of " + entry.getValue().size() + " elements)" + LINE_SEP);
                for (Integer index : entry.getValue()) {
                    writer.write(uniqueValidRows.get(index).toString() + LINE_SEP);
                }
                groupId++;
                writer.write(LINE_SEP);
            }

            for (int i = 0; i < uniqueValidRows.size(); i++) {
                if (!mapStringToGroups.containsKey(i)) {
                    writer.write("Group #" + groupId + " (consists of 1 element)" + LINE_SEP);
                    writer.write(uniqueValidRows.get(i).toString() + LINE_SEP);
                    writer.write(LINE_SEP);
                    groupId++;
                }
            }

            writer.write("All " + (groupId - 1) + " groups created." + LINE_SEP + LINE_SEP);

            long end = System.currentTimeMillis();
            long heapSize = Runtime.getRuntime().totalMemory();
            long heapFreeSize = Runtime.getRuntime().freeMemory();

            writer.write("Total time: " + (end - start) + " millis" + LINE_SEP);
            writer.write("Total memory: " + formatSize(heapSize - heapFreeSize) + LINE_SEP);

            logger.info("Total time: " + (end - start) + " millis");
            logger.info("Total memory: " + formatSize(heapSize - heapFreeSize));
        }
    }
}
