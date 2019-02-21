require_relative '../spec_helper'
describe service('ntp') do
    it { should be_enabled }
end
