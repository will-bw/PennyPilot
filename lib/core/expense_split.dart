/// Equal sharing of one payment; the participant count includes the user.
class ExpenseSplit {
  ExpenseSplit({required this.totalAmountFen, required this.participantCount}) {
    if (totalAmountFen <= 0 ||
        totalAmountFen > maxTotalAmountFen ||
        participantCount < 2 ||
        personalAmountFen < 1) {
      throw const FormatException('AA 总额必须大于 0，人数至少为 2，个人分摊至少为 0.01 元。');
    }
  }

  final int totalAmountFen;
  final int participantCount;
  static const maxTotalAmountFen = 999999999;

  /// Round half up to the nearest fen without floating point arithmetic.
  int get personalAmountFen =>
      _personalAmount(totalAmountFen, participantCount);

  static int _personalAmount(int total, int count) =>
      total ~/ count + (total % count >= count ~/ 2 + count % 2 ? 1 : 0);

  Map<String, dynamic> toMap() => {
    'totalAmountFen': totalAmountFen,
    'participantCount': participantCount,
  };

  static ExpenseSplit? fromMap(Object? value) {
    if (value is! Map ||
        value['totalAmountFen'] is! int ||
        value['participantCount'] is! int) {
      return null;
    }
    final total = value['totalAmountFen'] as int;
    final count = value['participantCount'] as int;
    if (total <= 0 ||
        total > maxTotalAmountFen ||
        count < 2 ||
        _personalAmount(total, count) < 1) {
      return null;
    }
    return ExpenseSplit(totalAmountFen: total, participantCount: count);
  }

  static bool isRequested(String input) => RegExp(
    r'(?<![a-z])AA(?:制)?(?![a-z])|均摊|平摊|均分|平分',
    caseSensitive: false,
  ).hasMatch(input);

  bool hasParticipantEvidence(String input) {
    final counts = _participantCounts(input);
    return counts != null &&
        counts.length == 1 &&
        counts.contains(participantCount);
  }

  static bool hasParticipantDeclaration(String input) =>
      _participantCounts(input) != null;

  // A clarification replaces earlier headcounts. Conflicting counts within the
  // same turn require clarification rather than choosing one of the matches.
  static Set<int>? _participantCounts(String input) {
    final turns = input.split(RegExp(r'[，\n]补充[：:]'));
    final pattern = RegExp(
      r'(\d+|[零〇一二两三四五六七八九十百]+)\s*(?:个)?\s*(?:人|位)|人数\s*(?:改成|改为|改到|是|为|=|：|:)?\s*(\d+|[零〇一二两三四五六七八九十百]+)',
    );
    for (var index = turns.length - 1; index >= 0; index--) {
      final turn = turns[index];
      final matches = pattern.allMatches(turn).toList();
      if (matches.isNotEmpty) {
        final counts = <int>{};
        for (final match in matches) {
          final before = turn.substring(0, match.start).trimRight();
          // “我和三个人” states the number of other people, not the total.
          if (RegExp(r'(?:我|本人)\s*(?:和|跟|与)$').hasMatch(before)) return {};
          final count = _count((match[1] ?? match[2])!);
          if (count == null) return {};
          counts.add(count);
        }
        return counts;
      }
      if (index > 0 && isRequested(input)) {
        final bare = RegExp(
          r'^\s*(\d+|[零〇一二两三四五六七八九十百]+)\s*(?:个|人|位)?\s*$',
        ).firstMatch(turn);
        if (bare != null) {
          final count = _count(bare[1]!);
          return count == null ? {} : {count};
        }
      }
    }
    return null;
  }

  static int? _count(String text) {
    final numeric = int.tryParse(text);
    if (numeric != null) return numeric;
    const digits = {
      '零': 0,
      '〇': 0,
      '一': 1,
      '二': 2,
      '两': 2,
      '三': 3,
      '四': 4,
      '五': 5,
      '六': 6,
      '七': 7,
      '八': 8,
      '九': 9,
    };
    var result = 0, current = 0;
    for (final char in text.split('')) {
      if (digits.containsKey(char)) {
        current = digits[char]!;
      } else if (char == '十' || char == '百') {
        result += (current == 0 ? 1 : current) * (char == '十' ? 10 : 100);
        current = 0;
      } else {
        return null;
      }
    }
    return result + current;
  }
}
